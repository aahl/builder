// Modified or added for XiaoSeek LX04 Web Setup. See NOTICE.
// SPDX-License-Identifier: Apache-2.0
// lx04-websetup serves a LAN-only configuration page on the speaker. It collects
// the owner's private settings, writes the same config/device-match files the CLI
// flow uses, then drives the already-tested lx04-bootstrap subprocess to perform
// Xiaomi login, exact device matching, token persistence and the optional TTS
// test. The OTP is supplied back through the page instead of an adb-pushed file.
package main

import (
	"crypto/rand"
	"embed"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"io/fs"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"xiaobot"
)

//go:embed setup.html
var page embed.FS

const (
	defaultConfig = "/data/adb/lx04-deepseek/config.json"
	statusName    = "bootstrap-status.json"
	otpName       = "otp.txt"
	urlName       = "websetup.url"
	logName       = "websetup.log"
	urlTimeout    = 24 * time.Hour
)

var (
	mu          sync.Mutex
	setupToken  string
	cfgPath     string
	devicePath  string
	appDir      string
	bootstrap   string
	urlIssuedAt time.Time
	running     bool
	runErr      string
)

type setupInput struct {
	Account       string `json:"account"`
	Password      string `json:"password"`
	SerialNumber  string `json:"serial_number"`
	Mac           string `json:"mac"`
	DNS           string `json:"dns"`
	OpenAIKey     string `json:"openai_key"`
	Bot           string `json:"bot"`
	OpenAIBackend string `json:"openai_backend"`
	Prompt        string `json:"prompt"`
	UseCommand    bool   `json:"use_command"`
	Speak         bool   `json:"speak"`

	ContextMode   string `json:"context_mode"`
	ContextTurns  int    `json:"context_turns"`
	ContextTTLSec int    `json:"context_ttl_sec"`
	ThinkingMode  string `json:"thinking_mode"`
}

func newToken() (string, error) {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

// validDNS mirrors runtimeenv.ConfigureDNSFromEnv's literal-IP requirement so
// the browser cannot hand the bootstrap subprocess an unparsable resolver.
func validDNS(v string) bool {
	v = strings.TrimSpace(v)
	if v == "" {
		return true // bootstrap inherits the launcher's DNS setting
	}
	host, port, err := net.SplitHostPort(v)
	if err != nil || net.ParseIP(host) == nil {
		return false
	}
	n, err := strconv.Atoi(port)
	return err == nil && n > 0 && n <= 65535
}

func atomicWrite(path string, data []byte, mode os.FileMode) error {
	tmp := path + ".websetup.tmp"
	if err := os.WriteFile(tmp, data, mode); err != nil {
		return err
	}
	if err := os.Chmod(tmp, mode); err != nil {
		os.Remove(tmp)
		return err
	}
	if err := os.Rename(tmp, path); err != nil {
		os.Remove(tmp)
		return err
	}
	return os.Chmod(path, mode)
}

// buildConfig merges the submitted form into the existing private config so
// advanced fields that the page does not expose (gpt_options, music_path, ...)
// survive a web save.
func buildConfig(in setupInput, existing *xiaobot.Config) xiaobot.Config {
	cfg := xiaobot.Config{}
	if existing != nil {
		cfg = *existing
	}
	bot := strings.TrimSpace(in.Bot)
	if bot == "" {
		bot = "deepseek-flash"
	}
	backend := strings.TrimSpace(in.OpenAIBackend)
	if backend == "" {
		backend = "https://api.deepseek.com"
	}
	prompt := strings.TrimSpace(in.Prompt)
	if prompt == "" {
		prompt = "请用简洁自然的中文回答，适合语音朗读，通常控制在150字以内。不要编造实时天气或声称已经控制设备。"
	}
	cfg.Hardware = "LX04"
	cfg.Account = strings.TrimSpace(in.Account)
	cfg.Password = in.Password
	cfg.Bot = bot
	cfg.OpenAIKey = strings.TrimSpace(in.OpenAIKey)
	cfg.OpenAIBackend = backend
	cfg.UseCommand = in.UseCommand
	cfg.Stream = false
	cfg.Verbose = false
	cfg.Prompt = prompt
	cfg.ContextMode = in.ContextMode
	cfg.ContextTurns = in.ContextTurns
	cfg.ContextTTLSec = in.ContextTTLSec
	cfg.ThinkingMode = in.ThinkingMode
	cfg.Normalize()
	return cfg
}

// statusResponse intentionally exposes only non-secret state. It never reads
// config.json or .mi.token, so credentials and signed URLs cannot leak via GET.
type statusResponse struct {
	Status          string `json:"status"`
	Detail          string `json:"detail,omitempty"`
	VerificationMsg string `json:"verification_method,omitempty"`
	ExactMatches    *int   `json:"exact_matches,omitempty"`
	DidConfigured   bool   `json:"did_configured,omitempty"`
	SpeechTestSent  bool   `json:"speech_test_sent,omitempty"`
	Running         bool   `json:"running"`
	LastError       string `json:"last_error,omitempty"`
}

func readStatus() statusResponse {
	res := statusResponse{Running: running, LastError: runErr, Status: "idle"}
	data, err := os.ReadFile(filepath.Join(appDir, statusName))
	if err != nil {
		return res
	}
	var raw map[string]interface{}
	if json.Unmarshal(data, &raw) != nil {
		return res
	}
	if v, ok := raw["status"].(string); ok {
		res.Status = v
	}
	if v, ok := raw["verification_method"].(string); ok {
		res.VerificationMsg = v
	}
	if v, ok := raw["exact_matches"].(float64); ok {
		n := int(v)
		res.ExactMatches = &n
	}
	if v, ok := raw["did_configured"].(bool); ok {
		res.DidConfigured = v
	}
	if v, ok := raw["speech_test_sent"].(bool); ok {
		res.SpeechTestSent = v
	}
	if v, ok := raw["error"].(string); ok && v != "" {
		res.Detail = v
	}
	if v, ok := raw["login_code"].(float64); ok {
		res.Detail = fmt.Sprintf("Xiaomi login rejected (code %d)", int(v))
	}
	return res
}

func authorized(r *http.Request) bool {
	token := r.URL.Query().Get("token")
	if token == "" && r.Method == http.MethodPost {
		var body struct {
			Token string `json:"token"`
		}
		data, _ := io.ReadAll(io.LimitReader(r.Body, 1<<20))
		if json.Unmarshal(data, &body) == nil {
			token = body.Token
			// Restore a body for handlers that still need the JSON payload.
			r.Body = io.NopCloser(strings.NewReader(string(data)))
		}
	}
	valid := token != "" && subtleEqual(token, setupToken)
	mu.Lock()
	stale := !urlIssuedAt.IsZero() && time.Since(urlIssuedAt) > urlTimeout
	mu.Unlock()
	return valid && !stale
}

// subtleEqual avoids timing leaks on the setup token without adding a dependency.
func subtleEqual(a, b string) bool {
	if len(a) != len(b) {
		return false
	}
	var diff byte
	for i := range a {
		diff |= a[i] ^ b[i]
	}
	return diff == 0
}

func handlePage(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	content, err := fs.ReadFile(page, "setup.html")
	if err != nil {
		http.Error(w, "page unavailable", http.StatusInternalServerError)
		return
	}
	token := r.URL.Query().Get("token")
	content = []byte(strings.ReplaceAll(string(content), "__WEBSETUP_TOKEN__", token))
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Write(content)
}

func handleStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	json.NewEncoder(w).Encode(readStatus())
}

type deviceMatch struct {
	SerialNumber string `json:"serial_number"`
	Mac          string `json:"mac"`
}

type publicConfig struct {
	Configured    bool   `json:"configured"`
	Account       string `json:"account"`
	SerialNumber  string `json:"serial_number"`
	Mac           string `json:"mac"`
	DNS           string `json:"dns"`
	Bot           string `json:"bot"`
	OpenAIBackend string `json:"openai_backend"`
	Prompt        string `json:"prompt"`
	ContextMode   string `json:"context_mode"`
	ContextTurns  int    `json:"context_turns"`
	ContextTTLSec int    `json:"context_ttl_sec"`
	ThinkingMode  string `json:"thinking_mode"`
}

func loadDeviceMatch() deviceMatch {
	var dm deviceMatch
	if data, err := os.ReadFile(devicePath); err == nil {
		_ = json.Unmarshal(data, &dm)
	}
	return dm
}

func loadDNS() string {
	data, err := os.ReadFile(filepath.Join(appDir, "dns-server.txt"))
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(data))
}

func buildPublicConfig(cfg *xiaobot.Config, dm deviceMatch, dns string) publicConfig {
	out := publicConfig{
		SerialNumber:  dm.SerialNumber,
		Mac:           dm.Mac,
		DNS:           dns,
		Bot:           "deepseek-flash",
		OpenAIBackend: "https://api.deepseek.com",
		ContextMode:   "session",
		ContextTurns:  20,
		ThinkingMode:  "off",
	}
	if cfg == nil {
		return out
	}
	out.Configured = true
	out.Account = cfg.Account
	out.Bot = cfg.Bot
	out.OpenAIBackend = cfg.OpenAIBackend
	out.Prompt = cfg.Prompt
	out.ContextMode = cfg.ContextMode
	out.ContextTurns = cfg.ContextTurns
	out.ContextTTLSec = cfg.ContextTTLSec
	out.ThinkingMode = cfg.ThinkingMode
	return out
}

func handleConfig(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	cfg, _ := xiaobot.NewConfigFromFile(cfgPath)
	out := buildPublicConfig(cfg, loadDeviceMatch(), loadDNS())
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	json.NewEncoder(w).Encode(out)
}

func validateRuntime(mode string, turns, ttl int, thinking string) (string, int, int, string, error) {
	switch mode {
	case "":
		mode = "session"
	case "off", "session", "speaker":
	default:
		return "", 0, 0, "", fmt.Errorf("上下文模式无效")
	}
	if turns == 0 {
		turns = 20
	}
	if turns < 1 || turns > 200 {
		return "", 0, 0, "", fmt.Errorf("保留轮数必须在 1–200 之间")
	}
	if ttl < 0 || ttl > 1800 {
		return "", 0, 0, "", fmt.Errorf("空闲超时必须在 0–1800 秒之间")
	}
	switch thinking {
	case "":
		thinking = "off"
	case "off", "low", "high", "max":
	default:
		return "", 0, 0, "", fmt.Errorf("思考模式无效")
	}
	return mode, turns, ttl, thinking, nil
}

type runtimeInput struct {
	ContextMode   string `json:"context_mode"`
	ContextTurns  int    `json:"context_turns"`
	ContextTTLSec int    `json:"context_ttl_sec"`
	ThinkingMode  string `json:"thinking_mode"`
}

func applyRuntime(cfg *xiaobot.Config, in runtimeInput) (xiaobot.Config, error) {
	if cfg == nil {
		return xiaobot.Config{}, fmt.Errorf("尚未完成账号配置")
	}
	mode, turns, ttl, thinking, err := validateRuntime(in.ContextMode, in.ContextTurns, in.ContextTTLSec, in.ThinkingMode)
	if err != nil {
		return xiaobot.Config{}, err
	}
	out := *cfg
	out.ContextMode = mode
	out.ContextTurns = turns
	out.ContextTTLSec = ttl
	out.ThinkingMode = thinking
	out.Normalize()
	return out, nil
}

func handleRuntime(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	var in runtimeInput
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&in); err != nil {
		http.Error(w, "invalid JSON body", http.StatusBadRequest)
		return
	}
	mu.Lock()
	busy := running
	mu.Unlock()
	if busy {
		http.Error(w, "已有配置流程正在运行", http.StatusConflict)
		return
	}
	var existing *xiaobot.Config
	if current, err := xiaobot.NewConfigFromFile(cfgPath); err == nil {
		existing = current
	}
	cfg, err := applyRuntime(existing, in)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	data, _ := json.MarshalIndent(cfg, "", "  ")
	if err := atomicWrite(cfgPath, append(data, '\n'), 0600); err != nil {
		http.Error(w, "配置写入失败", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{"status": "saved", "restart_required": true})
}

func handleSetup(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	var in setupInput
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&in); err != nil {
		http.Error(w, "invalid JSON body", http.StatusBadRequest)
		return
	}
	if strings.TrimSpace(in.Account) == "" || in.Password == "" {
		http.Error(w, "小米账号和密码不能为空", http.StatusBadRequest)
		return
	}
	if strings.TrimSpace(in.SerialNumber) == "" && strings.TrimSpace(in.Mac) == "" {
		http.Error(w, "序列号和 MAC 至少填写一项", http.StatusBadRequest)
		return
	}
	if !validDNS(in.DNS) {
		http.Error(w, "DNS 必须是 IP:端口，例如 192.168.1.1:53", http.StatusBadRequest)
		return
	}
	mode, turns, ttl, thinking, err := validateRuntime(in.ContextMode, in.ContextTurns, in.ContextTTLSec, in.ThinkingMode)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	in.ContextMode, in.ContextTurns, in.ContextTTLSec, in.ThinkingMode = mode, turns, ttl, thinking

	mu.Lock()
	if running {
		mu.Unlock()
		http.Error(w, "已有配置流程正在运行", http.StatusConflict)
		return
	}
	running = true
	runErr = ""
	mu.Unlock()

	var existing *xiaobot.Config
	if current, err := xiaobot.NewConfigFromFile(cfgPath); err == nil {
		existing = current
	}
	cfg := buildConfig(in, existing)
	cfgData, _ := json.MarshalIndent(cfg, "", "  ")
	deviceData, _ := json.MarshalIndent(map[string]string{
		"serial_number": strings.TrimSpace(in.SerialNumber),
		"mac":           strings.TrimSpace(in.Mac),
	}, "", "  ")

	// Clear stale state before launching the bootstrap subprocess.
	for _, name := range []string{otpName, statusName, "otp-session.json", "login-verification.json"} {
		_ = os.Remove(filepath.Join(appDir, name))
	}
	if err := atomicWrite(cfgPath, append(cfgData, '\n'), 0600); err != nil {
		mu.Lock()
		running, runErr = false, "config write failed"
		mu.Unlock()
		http.Error(w, "配置写入失败", http.StatusInternalServerError)
		return
	}
	if err := atomicWrite(devicePath, append(deviceData, '\n'), 0600); err != nil {
		mu.Lock()
		running, runErr = false, "device-match write failed"
		mu.Unlock()
		http.Error(w, "设备匹配文件写入失败", http.StatusInternalServerError)
		return
	}

	args := []string{"-config", cfgPath, "-device-file", devicePath}
	if in.Speak {
		args = append(args, "-speak")
	}
	cmd := exec.Command(bootstrap, args...)
	cmd.Env = append(os.Environ(),
		"HOME="+appDir,
		"SSL_CERT_DIR=/system/etc/security/cacerts",
		"XIAOBOT_DNS_SERVER="+strings.TrimSpace(in.DNS),
	)
	cmd.Dir = appDir
	logFile, err := os.OpenFile(filepath.Join(appDir, logName), os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0600)
	if err != nil {
		mu.Lock()
		running, runErr = false, "log open failed"
		mu.Unlock()
		http.Error(w, "日志打开失败", http.StatusInternalServerError)
		return
	}
	cmd.Stdout = logFile
	cmd.Stderr = logFile
	go func() {
		defer logFile.Close()
		done := make(chan error, 1)
		go func() { done <- cmd.Run() }()
		select {
		case err := <-done:
			mu.Lock()
			running = false
			if err != nil && runErr == "" {
				runErr = "bootstrap exited with error"
			}
			mu.Unlock()
		case <-time.After(15 * time.Minute):
			_ = cmd.Process.Kill()
			mu.Lock()
			running, runErr = false, "bootstrap timed out"
			mu.Unlock()
		}
	}()

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"status": "started"})
}

func handleOTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	var body struct {
		Token string `json:"token"`
		Code  string `json:"code"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&body); err != nil {
		http.Error(w, "invalid JSON body", http.StatusBadRequest)
		return
	}
	code := strings.TrimSpace(body.Code)
	if code == "" {
		http.Error(w, "验证码不能为空", http.StatusBadRequest)
		return
	}
	path := filepath.Join(appDir, otpName)
	if err := atomicWrite(path, []byte(code+"\n"), 0600); err != nil {
		http.Error(w, "验证码写入失败", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"status": "otp_accepted"})
}

func withAuth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !authorized(r) {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		next(w, r)
	}
}

func router() *http.ServeMux {
	mux := http.NewServeMux()
	mux.HandleFunc("/", withAuth(handlePage))
	mux.HandleFunc("/api/status", withAuth(handleStatus))
	mux.HandleFunc("/api/config", withAuth(handleConfig))
	mux.HandleFunc("/api/runtime", withAuth(handleRuntime))
	mux.HandleFunc("/api/setup", withAuth(handleSetup))
	mux.HandleFunc("/api/otp", withAuth(handleOTP))
	return mux
}

func main() {
	configFlag := flag.String("config", defaultConfig, "private config file")
	deviceFlag := flag.String("device-file", "", "private JSON with serial_number and/or mac")
	listen := flag.String("listen", "0.0.0.0:9997", "listen address for the setup page")
	bootstrapFlag := flag.String("bootstrap", "", "path to lx04-bootstrap binary")
	flag.Parse()

	cfgPath = *configFlag
	appDir = filepath.Dir(cfgPath)
	devicePath = *deviceFlag
	if devicePath == "" {
		devicePath = filepath.Join(appDir, "device-match.json")
	}
	bootstrap = *bootstrapFlag
	if bootstrap == "" {
		exe, err := os.Executable()
		if err != nil {
			log.Fatal("cannot resolve executable directory")
		}
		bootstrap = filepath.Join(filepath.Dir(exe), "lx04-bootstrap")
	}
	if _, err := os.Stat(bootstrap); err != nil {
		log.Fatalf("bootstrap binary unavailable: %v", err)
	}

	token, err := newToken()
	if err != nil {
		log.Fatal("cannot generate setup token")
	}
	setupToken = token
	urlIssuedAt = time.Now()

	host, port, err := net.SplitHostPort(*listen)
	if err != nil {
		log.Fatal("-listen must be host:port")
	}
	if host == "" || host == "0.0.0.0" {
		host = "<SPEAKER_IP>"
	}
	url := fmt.Sprintf("http://%s:%s/?token=%s", host, port, token)
	if err := atomicWrite(filepath.Join(appDir, urlName), []byte(url+"\n"), 0600); err != nil {
		log.Fatalf("cannot write %s: %v", urlName, err)
	}
	log.Printf("web setup ready: see %s for the one-time URL", urlName)

	server := &http.Server{
		Addr:              *listen,
		Handler:           router(),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      30 * time.Second,
		MaxHeaderBytes:    1 << 20,
	}
	log.Fatal(server.ListenAndServe())
}

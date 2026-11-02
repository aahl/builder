import TcpSocket from 'react-native-tcp-socket';
import NetInfo from '@react-native-community/netinfo';

const DEFAULT_PORT = 12346;

interface HttpRequest {
  method: string;
  url: string;
  headers: { [key: string]: string };
  body: string;
}

interface HttpResponse {
  statusCode: number;
  headers: { [key: string]: string };
  body: string;
}

type RequestHandler = (request: HttpRequest) => HttpResponse | Promise<HttpResponse>;

class TCPHttpServer {
  private server: TcpSocket.Server | null = null;
  private isRunning = false;
  private requestHandler: RequestHandler | null = null;
  private port: number;

  constructor(port: number = DEFAULT_PORT) {
    this.server = null;
    this.port = port;
  }

  private parseHttpRequest(data: string): HttpRequest | null {
    try {
      const lines = data.split('\r\n');
      const requestLine = lines[0].split(' ');
      
      if (requestLine.length < 3) {
        return null;
      }

      const method = requestLine[0];
      const url = requestLine[1];
      const headers: { [key: string]: string } = {};
      
      let bodyStartIndex = -1;
      for (let i = 1; i < lines.length; i++) {
        const line = lines[i];
        if (line === '') {
          bodyStartIndex = i + 1;
          break;
        }
        const colonIndex = line.indexOf(':');
        if (colonIndex > 0) {
          const key = line.substring(0, colonIndex).trim().toLowerCase();
          const value = line.substring(colonIndex + 1).trim();
          headers[key] = value;
        }
      }

      const body = bodyStartIndex > 0 ? lines.slice(bodyStartIndex).join('\r\n') : '';

      return { method, url, headers, body };
    } catch (error) {
      console.info('[TCPHttpServer] Error parsing HTTP request:', error);
      return null;
    }
  }

  private formatHttpResponse(response: HttpResponse): Buffer {
    const statusTexts: { [key: number]: string } = {
      200: 'OK',
      302: 'Found',
      400: 'Bad Request',
      404: 'Not Found',
      500: 'Internal Server Error'
    };

    const statusText = statusTexts[response.statusCode] || 'Unknown';
    const bodyBuf = Buffer.from(response.body ?? '', 'utf8');
    const headers: { [key: string]: string } = {
      'Content-Length': bodyBuf.length.toString(),
      'Connection': 'close',
      ...response.headers
    };

    let head = `HTTP/1.1 ${response.statusCode} ${statusText}\r\n`;
    for (const [key, value] of Object.entries(headers)) {
      head += `${key}: ${value}\r\n`;
    }
    head += '\r\n';

    // 二进制安全拼接头与体（Buffer.concat），避免中文/大响应被截断导致 unexpected end of stream
    return Buffer.concat([Buffer.from(head, 'utf8'), bodyBuf]);
  }

  public setRequestHandler(handler: RequestHandler) {
    this.requestHandler = handler;
  }

  public async start(): Promise<string> {
    const netState = await NetInfo.fetch();
    let ipAddress: string | null = null;
    
    if (netState.type === 'wifi' || netState.type === 'ethernet') {
      ipAddress = (netState.details as any)?.ipAddress ?? null;
    }

    if (!ipAddress) {
      throw new Error('无法获取IP地址，请确认设备已连接到WiFi或以太网。');
    }

    if (this.isRunning) {
      console.log('[TCPHttpServer] Server is already running.');
      return `http://${ipAddress}:${this.port}`;
    }

    return new Promise((resolve, reject) => {
      try {
        this.server = TcpSocket.createServer((socket: TcpSocket.Socket) => {
          console.log('[TCPHttpServer] Client connected');
          
          let requestData = '';
          let handled = false;
          
          socket.on('data', async (data: string | Buffer) => {
            if (handled) return;
            requestData += data.toString();
            
            // Check if we have a complete HTTP request
            if (requestData.includes('\r\n\r\n')) {
              handled = true;
              let outBuf: Buffer;
              try {
                const request = this.parseHttpRequest(requestData);
                if (request && this.requestHandler) {
                  const response = await this.requestHandler(request);
                  outBuf = this.formatHttpResponse(response);
                } else {
                  outBuf = this.formatHttpResponse({
                    statusCode: 400,
                    headers: { 'Content-Type': 'text/plain' },
                    body: 'Bad Request'
                  });
                }
              } catch (error) {
                console.info('[TCPHttpServer] Error handling request:', error);
                outBuf = this.formatHttpResponse({
                  statusCode: 500,
                  headers: { 'Content-Type': 'text/plain' },
                  body: 'Internal Server Error'
                });
              }
              
              requestData = '';
              // 写入后立即 end()（Node 语义：会在数据全部发出后发送 FIN，不截断）
              try {
                socket.write(outBuf);
              } catch {}
              try {
                socket.end();
              } catch {}
            }
          });

          socket.on('error', (error: Error) => {
            console.info('[TCPHttpServer] Socket error:', error);
          });

          socket.on('close', () => {
            console.log('[TCPHttpServer] Client disconnected');
          });
        });

        this.server.listen({ port: this.port, host: '0.0.0.0' }, () => {
          console.log(`[TCPHttpServer] Server listening on ${ipAddress}:${this.port}`);
          this.isRunning = true;
          resolve(`http://${ipAddress}:${this.port}`);
        });

        this.server.on('error', (error: Error) => {
          console.info('[TCPHttpServer] Server error:', error);
          this.isRunning = false;
          reject(error);
        });

      } catch (error) {
        console.info('[TCPHttpServer] Failed to start server:', error);
        reject(error);
      }
    });
  }

  public stop() {
    if (this.server && this.isRunning) {
      this.server.close();
      this.server = null;
      this.isRunning = false;
      console.log('[TCPHttpServer] Server stopped');
    }
  }

  public getIsRunning(): boolean {
    return this.isRunning;
  }
}

export default TCPHttpServer;
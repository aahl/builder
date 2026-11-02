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

  private formatHttpResponse(response: HttpResponse): string {
    const statusTexts: { [key: number]: string } = {
      200: 'OK',
      302: 'Found',
      400: 'Bad Request',
      404: 'Not Found',
      500: 'Internal Server Error'
    };

    const statusText = statusTexts[response.statusCode] || 'Unknown';
    const body = response.body ?? '';
    // 注意：React Native(Hermes) 没有 Node 的 Buffer 全局对象，这里用 TextEncoder 取字节长度、
    // 以字符串写回（m3u8 内容均为 ASCII，字节长度=字符长度，Content-Length 精确）。
    const bodyLen = new TextEncoder().encode(body).length;
    const headers: { [key: string]: string } = {
      'Content-Length': bodyLen.toString(),
      'Connection': 'close',
      ...response.headers
    };

    let httpResponse = `HTTP/1.1 ${response.statusCode} ${statusText}\r\n`;
    for (const [key, value] of Object.entries(headers)) {
      httpResponse += `${key}: ${value}\r\n`;
    }
    httpResponse += '\r\n';
    httpResponse += body;
    return httpResponse;
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
              let outStr: string;
              try {
                const request = this.parseHttpRequest(requestData);
                if (request && this.requestHandler) {
                  const response = await this.requestHandler(request);
                  outStr = this.formatHttpResponse(response);
                } else {
                  outStr = this.formatHttpResponse({
                    statusCode: 400,
                    headers: { 'Content-Type': 'text/plain' },
                    body: 'Bad Request'
                  });
                }
              } catch (error) {
                console.info('[TCPHttpServer] Error handling request:', error);
                outStr = this.formatHttpResponse({
                  statusCode: 500,
                  headers: { 'Content-Type': 'text/plain' },
                  body: 'Internal Server Error'
                });
              }
              
              requestData = '';
              // 字符串写回（RN 无 Buffer）；Node 语义下 end() 会在数据发出后发送 FIN
              try {
                socket.write(outStr);
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
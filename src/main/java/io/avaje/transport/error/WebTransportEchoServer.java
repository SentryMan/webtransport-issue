package io.avaje.transport.error;

import java.io.IOException;
import java.net.URI;
import java.security.KeyStore;
import java.time.Duration;

import tech.kwik.core.log.Logger;
import tech.kwik.core.log.SysOutLogger;
import tech.kwik.core.server.ServerConnector;
import tech.kwik.flupke.Http3Client;
import tech.kwik.flupke.HttpError;
import tech.kwik.flupke.server.HttpRequestHandler;
import tech.kwik.flupke.webtransport.ClientSessionFactory;
import tech.kwik.flupke.webtransport.Session;
import tech.kwik.flupke.webtransport.WebTransportHttp3ApplicationProtocolFactory;
import tech.kwik.flupke.webtransport.WebTransportStream;

/** A simple echo server for WebTransport. */
public class WebTransportEchoServer {

  public static void main() throws Exception {

    new WebTransportEchoServer().start();
  }

  private void start() throws Exception {
    SysOutLogger log = new SysOutLogger();
    log.logWarning(true);
    var keystore = KeyStore.getInstance("PKCS12");

    var password = "password";
    keystore.load(
        WebTransportEchoServer.class.getResourceAsStream("/my-custom-keystore.p12"),
        password.toCharArray());

    var certificateAlias = keystore.aliases().nextElement();

    ServerConnector serverConnector =
        ServerConnector.builder()
            .withPort(8080)
            .withKeyStore(keystore, certificateAlias, password.toCharArray())
            .withLogger(log)
            .build();

    // Set a handler for non-CONNECT requests; a no-op handler (e.g. (req, resp) -> {} ) would also
    // work, but would
    // always return an error status (4xx), which might be confusing.
    HttpRequestHandler httpRequestHandler =
        (request, response) -> {
          if ("GET".equals(request.method())) {
            response.setStatus(200);
          } else {
            response.setStatus(405);
          }
        };

    WebTransportHttp3ApplicationProtocolFactory webTransportProtocolFactory =
        new WebTransportHttp3ApplicationProtocolFactory(httpRequestHandler);
    webTransportProtocolFactory.registerWebTransportServer("/echo", this::startEchoHandler);
    webTransportProtocolFactory.registerWebTransportServer("/", this::startEchoHandler);
    serverConnector.registerApplicationProtocol("h3", webTransportProtocolFactory);
    serverConnector.start();

    URI serverUrl = URI.create("https://localhost:8080/echo");

    Logger stdoutLogger = new SysOutLogger();

    Http3Client client =
        (Http3Client)
            Http3Client.newBuilder()
                .disableCertificateCheck()
                .logger(stdoutLogger)
                .connectTimeout(Duration.ofDays(1))
                .build();

    try {
      ClientSessionFactory clientSessionFactory =
          ClientSessionFactory.newBuilder().serverUri(serverUrl).httpClient(client).build();

      Session session = clientSessionFactory.createSession(serverUrl);
      session.registerSessionTerminatedEventListener(
          (errorCode, message) -> {
            System.out.println(
                "Session " + session.getSessionId() + " closed with error code " + errorCode);
          });

      session.open();
      System.out.println("Session " + session.getSessionId() + " opened to " + serverUrl);
      WebTransportStream bidirectionalStream = session.createBidirectionalStream();

      String message = "Hello, world!";
      bidirectionalStream.getOutputStream().write(message.getBytes());
      System.out.println("Request sent to " + serverUrl + ": " + message);
      bidirectionalStream.getOutputStream().close();
      System.out.print("Response: ");
      bidirectionalStream.getInputStream().transferTo(System.out);
      System.out.println();
      session.close();
      System.out.println("Session closed. ");

      System.out.println("That's it! Bye!");
    } catch (IOException | HttpError e) {
      System.err.println("Request failed: " + e.getMessage());
      e.printStackTrace();
      throw e;
    } finally {
      serverConnector.close();
    }
  }

  private void startEchoHandler(Session session) {

    System.out.println("Starting echo handler for WebTransport session: " + session.getSessionId());

    session.registerSessionTerminatedEventListener(
        (errorCode, message) -> {
          System.out.println(
              "Session " + session.getSessionId() + " closed with error code " + errorCode);
        });

    session.setBidirectionalStreamReceiveHandler(
        stream -> {
          try {
            stream.getInputStream().transferTo(stream.getOutputStream());

            System.out.println(
                "Processed a request for session " + session.getSessionId() + " response sent");
            stream.getOutputStream().close();
          } catch (IOException e) {
            System.out.println("IO error while processing request: " + e.getMessage());
          }
        });

    // Make sure the session is opened _after_ setting the handlers, otherwise we might miss
    // incoming streams.
    session.open();
  }
}

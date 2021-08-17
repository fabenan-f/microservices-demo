/*
 * Copyright 2018, Google LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package hipstershop;

import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import hipstershop.Demo.Ad;
import hipstershop.Demo.AdRequest;
import hipstershop.Demo.AdResponse;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.services.*;
import io.grpc.stub.StreamObserver;

import io.opentelemetry.exporter.jaeger.JaegerGrpcSpanExporter;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.semconv.resource.attributes.ResourceAttributes;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class AdService {

  private static final Logger logger = LogManager.getLogger(AdService.class);
  // private static final Tracer tracer = Tracing.getTracer();

  @SuppressWarnings("FieldCanBeLocal")
  private static int MAX_ADS_TO_SERVE = 2;

  private Server server;
  // private HealthStatusManager healthMgr;

  private static final AdService service = new AdService();

  // Extract the Distributed Context from the gRPC metadata
  private static final TextMapGetter<Metadata> getter =
      new TextMapGetter<Metadata>() {
        @Override
        public Iterable<String> keys(Metadata carrier) {
          return carrier.keys();
        }

        @Override
        public String get(Metadata carrier, String key) {
          Metadata.Key<String> k = Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER);
          if (carrier.containsKey(k)) {
            return carrier.get(k);
          }
          return "";
        }
      };

  private static final OpenTelemetry openTelemetry = configOtelTracing();
  private static final TextMapPropagator textFormat = openTelemetry.getPropagators().getTextMapPropagator();

  private void start() throws IOException {
    int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "9555"));
    // healthMgr = new HealthStatusManager();

    server =
        NettyServerBuilder.forPort(port)
            .addService(new AdServiceImpl())
            // .maxConnectionAge(60, TimeUnit.SECONDS)
            .keepAliveTimeout(30, TimeUnit.SECONDS)
            // Intercept gRPC calls
            .intercept(new OpenTelemetryServerInterceptor())
            // .addService(healthMgr.getHealthService())
            .build()
            .start();
    logger.info("Ad Service started, listening on " + port);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  // Use stderr here since the logger may have been reset by its JVM shutdown hook.
                  System.err.println(
                      "*** shutting down gRPC ads server since JVM is shutting down");
                  AdService.this.stop();
                  System.err.println("*** server shut down");
                }));
    // healthMgr.setStatus("", ServingStatus.SERVING);
  }

  private void stop() {
    if (server != null) {
      // healthMgr.clearStatus("");
      server.shutdown();
    }
  }

  private static class AdServiceImpl extends hipstershop.AdServiceGrpc.AdServiceImplBase {

    /**
     * Retrieves ads based on context provided in the request {@code AdRequest}.
     *
     * @param req the request containing context.
     * @param responseObserver the stream observer which gets notified with the value of {@code
     *     AdResponse}
     */
    @Override
    public void getAds(AdRequest req, StreamObserver<AdResponse> responseObserver) {
      AdService service = AdService.getInstance();
      try {
        List<Ad> allAds = new ArrayList<>();
        logger.info("received ad request (context_words=" + req.getContextKeysList() + ")");
        if (req.getContextKeysCount() > 0) {
          for (int i = 0; i < req.getContextKeysCount(); i++) {
            Collection<Ad> ads = service.getAdsByCategory(req.getContextKeys(i));
            allAds.addAll(ads);
          }
        } else {
          allAds = service.getRandomAds();
        }
        if (allAds.isEmpty()) {
          // Serve random ads.
          allAds = service.getRandomAds();
        }
        AdResponse reply = AdResponse.newBuilder().addAllAds(allAds).build();
        responseObserver.onNext(reply);
        responseObserver.onCompleted();
      } catch (StatusRuntimeException e) {
        logger.log(Level.WARN, "GetAds Failed with status {}", e.getStatus());
        responseObserver.onError(e);
      }
    }
  }

  private static final ImmutableListMultimap<String, Ad> adsMap = createAdsMap();

  private Collection<Ad> getAdsByCategory(String category) {
    return adsMap.get(category);
  }

  private static final Random random = new Random();

  private List<Ad> getRandomAds() {
    List<Ad> ads = new ArrayList<>(MAX_ADS_TO_SERVE);
    Collection<Ad> allAds = adsMap.values();
    for (int i = 0; i < MAX_ADS_TO_SERVE; i++) {
      ads.add(Iterables.get(allAds, random.nextInt(allAds.size())));
    }
    return ads;
  }

  private static AdService getInstance() {
    return service;
  }

  /** Await termination on the main thread since the grpc library uses daemon threads. */
  private void blockUntilShutdown() throws InterruptedException {
    if (server != null) {
      server.awaitTermination();
    }
  }

  private static ImmutableListMultimap<String, Ad> createAdsMap() {
    Ad camera =
        Ad.newBuilder()
            .setRedirectUrl("/product/2ZYFJ3GM2N")
            .setText("Film camera for sale. 50% off.")
            .build();
    Ad lens =
        Ad.newBuilder()
            .setRedirectUrl("/product/66VCHSJNUP")
            .setText("Vintage camera lens for sale. 20% off.")
            .build();
    Ad recordPlayer =
        Ad.newBuilder()
            .setRedirectUrl("/product/0PUK6V6EV0")
            .setText("Vintage record player for sale. 30% off.")
            .build();
    Ad bike =
        Ad.newBuilder()
            .setRedirectUrl("/product/9SIQT8TOJO")
            .setText("City Bike for sale. 10% off.")
            .build();
    Ad baristaKit =
        Ad.newBuilder()
            .setRedirectUrl("/product/1YMWWN1N4O")
            .setText("Home Barista kitchen kit for sale. Buy one, get second kit for free")
            .build();
    Ad airPlant =
        Ad.newBuilder()
            .setRedirectUrl("/product/6E92ZMYYFZ")
            .setText("Air plants for sale. Buy two, get third one for free")
            .build();
    Ad terrarium =
        Ad.newBuilder()
            .setRedirectUrl("/product/L9ECAV7KIM")
            .setText("Terrarium for sale. Buy one, get second one for free")
            .build();
    return ImmutableListMultimap.<String, Ad>builder()
        .putAll("photography", camera, lens)
        .putAll("vintage", camera, lens, recordPlayer)
        .put("cycling", bike)
        .put("cookware", baristaKit)
        .putAll("gardening", airPlant, terrarium)
        .build();
  }

  private class OpenTelemetryServerInterceptor implements io.grpc.ServerInterceptor {
    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
      // Extract the Span Context from the metadata of the gRPC request
      Context extractedContext = textFormat.extract(Context.current(), headers, getter);
      InetSocketAddress clientInfo =
          (InetSocketAddress) call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
      // Build a span based on the received context
      Tracer tracer = openTelemetry.getTracer("test");
      Span span =
          tracer
              .spanBuilder("helloworld.Greeter/SayHello")
              .setParent(extractedContext)
              .setSpanKind(SpanKind.SERVER)
              .startSpan();
      try (Scope innerScope = span.makeCurrent()) {
        span.setAttribute("component", "grpc");
        span.setAttribute("rpc.service", "Greeter");
        span.setAttribute("net.peer.ip", clientInfo.getHostString());
        span.setAttribute("net.peer.port", clientInfo.getPort());
        try {
          Thread.sleep(TimeUnit.SECONDS.toMillis(0));
        } catch (Exception se) {
          span.setStatus(StatusCode.ERROR, "time out error occured");
          logger.log(Level.WARN, "Exception while sleeping" + se.toString());
        }
    
        // Process the gRPC call normally
        return Contexts.interceptCall(io.grpc.Context.current(), call, headers, next);
      } finally {
        span.end();
      }
    }
  }

  private static OpenTelemetry configOtelTracing() {
    // Wait 'til services are available.
    try {
      Thread.sleep(TimeUnit.SECONDS.toMillis(60));
    } catch (Exception se) {
      logger.log(Level.WARN, "Exception while sleeping" + se.toString());
    }

    // Jaeger exporter needs to be configured.
    JaegerGrpcSpanExporter jaegerExporter = JaegerGrpcSpanExporter.builder()
      .setEndpoint("http://" + System.getenv("JAEGER_COLLECTOR_SERVICE_HOST") + ":14250/api/v2/spans")
      .setTimeout(30, TimeUnit.SECONDS)
      .build();

    // Resources apply to all spans generated by process.
    Resource serviceNameResource = Resource
      .create(Attributes.of(ResourceAttributes.SERVICE_NAME, "adservice"));

    SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
      // Completed spans will be sent to exporter synchronously (in real scenarios batching should be considered).
      .addSpanProcessor(BatchSpanProcessor.builder(jaegerExporter).build())
      .setResource(Resource.getDefault().merge(serviceNameResource))
      .setSampler(Sampler.traceIdRatioBased​(0.45))
      .build();
      
    OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
      // Registration of global trace provider.
      .setTracerProvider(tracerProvider)
      // Context will be propagated.
      .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
      .buildAndRegisterGlobal();

    Runtime.getRuntime().addShutdownHook(new Thread(tracerProvider::close));
    
    logger.info("Tracing is enabled");

    // Tracer tracer = tracerProvider.get("adservice.test.1");

    // Span testSpan = tracer.spanBuilder("testSpan").startSpan();
    // testSpan.end();

    // logger.info("span are exported");

    return openTelemetry;

  }

  /** Main launches the server from the command line. */
  public static void main(String[] args) throws IOException, InterruptedException {
    // Start the RPC server. You shouldn't see any output from gRPC before this.
    logger.info("AdService starting.");
    final AdService service = AdService.getInstance();
    service.start();
    service.blockUntilShutdown();
  }
}

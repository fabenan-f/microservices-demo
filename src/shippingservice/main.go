// Copyright 2018 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package main

import (
	"fmt"
	"net"
	"os"
	"time"

	"github.com/sirupsen/logrus"

	"golang.org/x/net/context"
	"google.golang.org/grpc"
	"google.golang.org/grpc/reflection"

	// "google.golang.org/grpc/codes"
	// "google.golang.org/grpc/status"

	grpcotel "go.opentelemetry.io/contrib/instrumentation/google.golang.org/grpc/otelgrpc"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/jaeger"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	tracesdk "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.4.0"

	pb "github.com/GoogleCloudPlatform/microservices-demo/src/shippingservice/genproto"
	// healthpb "google.golang.org/grpc/health/grpc_health_v1"
)

const (
	defaultPort = "50051"
)

var log *logrus.Logger

func init() {
	log = logrus.New()
	log.Level = logrus.DebugLevel
	log.Formatter = &logrus.JSONFormatter{
		FieldMap: logrus.FieldMap{
			logrus.FieldKeyTime:  "timestamp",
			logrus.FieldKeyLevel: "severity",
			logrus.FieldKeyMsg:   "message",
		},
		TimestampFormat: time.RFC3339Nano,
	}
	log.Out = os.Stdout
}

func main() {

	tp := configOtelTracing()
	defer func() {
		if err := tp.Shutdown(context.Background()); err != nil {
			log.Fatal(err)
		}
	}()

	port := defaultPort
	if value, ok := os.LookupEnv("PORT"); ok {
		port = value
	}
	port = fmt.Sprintf(":%s", port)

	lis, err := net.Listen("tcp", port)
	if err != nil {
		log.Fatalf("failed to listen: %v", err)
	}

	var srv *grpc.Server
	srv = grpc.NewServer(
		// Interceptors update response before it's returned to client
		grpc.UnaryInterceptor(grpcotel.UnaryServerInterceptor()),
		grpc.StreamInterceptor(grpcotel.StreamServerInterceptor()),
	)

	svc := &server{}
	pb.RegisterShippingServiceServer(srv, svc)
	// healthpb.RegisterHealthServer(srv, svc)
	log.Infof("Shipping Service listening on port %s", port)

	// Register reflection service on gRPC server.
	reflection.Register(srv)
	if err := srv.Serve(lis); err != nil {
		log.Fatalf("failed to serve: %v", err)
	}
}

// server controls RPC service responses.
type server struct{}

// Check is for health checking.
// func (s *server) Check(ctx context.Context, req *healthpb.HealthCheckRequest) (*healthpb.HealthCheckResponse, error) {
// 	return &healthpb.HealthCheckResponse{Status: healthpb.HealthCheckResponse_SERVING}, nil
// }

// func (s *server) Watch(req *healthpb.HealthCheckRequest, ws healthpb.Health_WatchServer) error {
// 	return status.Errorf(codes.Unimplemented, "health check via Watch not implemented")
// }

// GetQuote produces a shipping quote (cost) in USD.
func (s *server) GetQuote(ctx context.Context, in *pb.GetQuoteRequest) (*pb.GetQuoteResponse, error) {
	// log.Info("[GetQuote] received request")
	// defer log.Info("[GetQuote] completed request")

	tp := otel.GetTracerProvider()
	var tracer = tp.Tracer("src/shippingservice/GetQuote")
	_, span := tracer.Start(ctx, "hipstershop.ShippingService/GetQuote/GeneratingPriceQuote")
	defer span.End()

	// 1. Our quote system requires the total number of items to be shipped.
	count := 0
	for _, item := range in.Items {
		count += int(item.Quantity)
	}

	// 2. Generate a quote based on the total number of items to be shipped.
	quote := CreateQuoteFromCount(count)

	// 3. Generate a response.
	return &pb.GetQuoteResponse{
		CostUsd: &pb.Money{
			CurrencyCode: "USD",
			Units:        int64(quote.Dollars),
			Nanos:        int32(quote.Cents * 10000000)},
	}, nil

}

// ShipOrder mocks that the requested items will be shipped.
// It supplies a tracking ID for notional lookup of shipment delivery status.
func (s *server) ShipOrder(ctx context.Context, in *pb.ShipOrderRequest) (*pb.ShipOrderResponse, error) {
	// log.Info("[ShipOrder] received request")
	// defer log.Info("[ShipOrder] completed request")

	// 1. Create a Tracking ID
	baseAddress := fmt.Sprintf("%s, %s, %s", in.Address.StreetAddress, in.Address.City, in.Address.State)
	id := CreateTrackingId(baseAddress)

	// 2. Generate a response.
	return &pb.ShipOrderResponse{
		TrackingId: id,
	}, nil
}

func configOtelTracing() *tracesdk.TracerProvider {
	// Wait 'til services are available.
	d := time.Second * 30
	log.Infof("Sleeping %v to initialize exporter", d)
	time.Sleep(d)

	// Jaeger exporter needs to be configured.
	exp, err := jaeger.New(
		// Spans will be sent to jaeger collector directly.
		jaeger.WithCollectorEndpoint(jaeger.WithEndpoint("http://" + os.Getenv("JAEGER_COLLECTOR_SERVICE_HOST") + ":14268/api/traces")),
	)
	if err != nil {
		log.Fatal(err)
	}
	// Completed spans will be sent to exporter synchronously (in real scenarios batching should be considered).
	ssp := tracesdk.NewSimpleSpanProcessor(exp)

	// Resources apply to all spans generated by process.
	resources := resource.NewWithAttributes(
		semconv.SchemaURL,
		semconv.ServiceNameKey.String("shippingservice"),
	)

	tp := tracesdk.NewTracerProvider(
		tracesdk.WithSpanProcessor(ssp),
		tracesdk.WithResource(resources),
	)

	// Registration of global trace provider.
	otel.SetTracerProvider(tp)
	// Context will be propagated.
	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(propagation.TraceContext{}, propagation.Baggage{}))

	log.Info("Tracing is enabled!")
	return tp
}

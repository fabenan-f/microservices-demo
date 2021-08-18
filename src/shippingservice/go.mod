module github.com/GoogleCloudPlatform/microservices-demo/src/shippingservice

go 1.15

require (
	cloud.google.com/go v0.40.0 // indirect
	github.com/golang/protobuf v1.5.2
	github.com/konsorten/go-windows-terminal-sequences v1.0.2 // indirect
	github.com/sirupsen/logrus v1.4.2
	go.opentelemetry.io/contrib/instrumentation/google.golang.org/grpc/otelgrpc v0.21.0
	go.opentelemetry.io/otel v1.0.0-RC1
	go.opentelemetry.io/otel/exporters/jaeger v1.0.0-RC1
	go.opentelemetry.io/otel/sdk v1.0.0-RC1
	go.opentelemetry.io/otel/trace v1.0.0-RC1
	golang.org/x/net v0.0.0-20190923162816-aa69164e4478
	google.golang.org/appengine v1.6.1 // indirect
	google.golang.org/grpc v1.38.0
	gopkg.in/yaml.v3 v3.0.0-20210107192922-496545a6307b // indirect
)

replace git.apache.org/thrift.git v0.12.1-0.20190708170704-286eee16b147 => github.com/apache/thrift v0.12.1-0.20190708170704-286eee16b147

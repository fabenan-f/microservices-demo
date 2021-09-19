<p align="center">
<img src="src/frontend/static/icons/Hipster_HeroLogoCyan.svg" width="300" alt="Online Boutique" />
</p>


## About

**Online Boutique** is a cloud-native microservices demo application.
Online Boutique consists of a 10-tier microservices application. The application is a
web-based e-commerce app where users can browse items,
add them to the cart, and purchase them.

**Google uses this application to demonstrate use of technologies like
Kubernetes/GKE, Istio, Stackdriver, gRPC and OpenCensus**. This application
works on any Kubernetes cluster, as well as Google
Kubernetes Engine. It’s **easy to deploy with little to no configuration**.

In the course of my thesis, this application was modified such that three services are instrumented with [OpenTelemetry](https://opentelemetry.io/)'s provided instrumentation API. Tracing information of those three services will thereby be sent to a Jaeger backend which is also configured inside this kubernetes cluster. The three services are the [frontend](./src/frontend), the [shippingservice](./src/shippingservice), and the [adservice](./src/adservice).

The instructions to deploy this application and Jaeger locally can be found further below. More about the thesis can be found in this [repository](https://github.com/fabenan-f/ukulele).

## Architecture

**Online Boutique** is composed of 11 microservices written in different
languages that talk to each other over gRPC. See the [Development Principles](/docs/development-principles.md) doc for more information.

[![Architecture of
microservices](./docs/img/architecture-diagram.png)](./docs/img/architecture-diagram.png)

Find **Protocol Buffers Descriptions** at the [`./pb` directory](./pb).

| Service                                              | Language      | Description                                                                                                                       |
| ---------------------------------------------------- | ------------- | --------------------------------------------------------------------------------------------------------------------------------- |
| [frontend](./src/frontend)                           | Go            | Exposes an HTTP server to serve the website. Does not require signup/login and generates session IDs for all users automatically. |
| [cartservice](./src/cartservice)                     | C#            | Stores the items in the user's shopping cart in Redis and retrieves it.                                                           |
| [productcatalogservice](./src/productcatalogservice) | Go            | Provides the list of products from a JSON file and ability to search products and get individual products.                        |
| [currencyservice](./src/currencyservice)             | Node.js       | Converts one money amount to another currency. Uses real values fetched from European Central Bank. It's the highest QPS service. |
| [paymentservice](./src/paymentservice)               | Node.js       | Charges the given credit card info (mock) with the given amount and returns a transaction ID.                                     |
| [shippingservice](./src/shippingservice)             | Go            | Gives shipping cost estimates based on the shopping cart. Ships items to the given address (mock)                                 |
| [emailservice](./src/emailservice)                   | Python        | Sends users an order confirmation email (mock).                                                                                   |
| [checkoutservice](./src/checkoutservice)             | Go            | Retrieves user cart, prepares order and orchestrates the payment, shipping and the email notification.                            |
| [recommendationservice](./src/recommendationservice) | Python        | Recommends other products based on what's given in the cart.                                                                      |
| [adservice](./src/adservice)                         | Java          | Provides text ads based on given context words.                                                                                   |
| [loadgenerator](./src/loadgenerator)                 | Python/Locust | Continuously sends requests imitating realistic user shopping flows to the frontend.                                              |

## Local Development

If you want to deploy this application locally, here is a quick guide. It's an andjusted version of the minikube deployment of the [Development Guide](/docs/development-guide.md).

1. You need to start Docker with at least following resource requirements in order to run the application properly:
- CPUs: 4
- Memory: 4.25 GB
- Disk image size: 32 GB
2. Start minikube with following command
```
minikube start --cpus=4 --memory 4096 --disk-size 32g
```  
3. Run `skaffold run`. This will build and deploy the application according to the [skaffold.yaml](/skaffold.yaml) configs where Jaeger is included
5. Run `kubectl get pod` to verify the pods are ready and running.
6. Access the web frontend through your cluster by running following command
```
minikube service frontend-external
```
7. Access the Jaeger UI through your cluster by running following command
```
minikube service jaeger-query
```
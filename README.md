# ELPA Service Deployments

## Directory Structure

base: This directory contains the foundational Kubernetes resources for the application, including a Deployment, Service, and a common ConfigMap. These serve as the template for all environments.

components: Inside the base and dev directories, you'll find components for technologies like Kafka and PostgreSQL. These components add specific configurations (e.g., environment variables for connection details) to the base resources.

Overlays (dev, abn, prod): These directories represent the different deployment environments. Each contains a kustomization.yaml that specifies which resources and components to use from the base and applies environment-specific settings.

example: this directory is where the expectation output are defined.

## Deployment Workflow

To deploy a service (e.g., hint-service) to a specific environment (e.g., dev), you would run the following command from the root of the project:

``` [bash]
kustomize build envs/dev/hint-service | kubectl apply -f -
```

This command will:

Start with the resources in the base directory.

Apply the Kafka and PostgreSQL components from both the base and dev directories, adding the necessary environment variables.

Apply the dev environment's specific configurations, such as labels, image tags, and replica counts, as defined in dev/hint-service/kustomization.yaml.

The final, merged YAML output is then piped to kubectl to be applied to your Kubernetes cluster.

This implementation allows you to keep your configurations DRY (Don't Repeat Yourself) by defining common settings in the base and only specifying the differences in each environment's overlay.
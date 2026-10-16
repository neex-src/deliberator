# The estate on the runtime's Floci: fake credentials, account 000000000000, every endpoint on the
# emulator, validation flags off. `bb estate:deploy` passes `endpoint` from the workspace's local.edn.
terraform {
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.0" }
  }
}

variable "endpoint" {
  type    = string
  default = "http://localhost:4566"
}

variable "container_endpoint" {
  type        = string
  default     = "http://deliberator-default-floci:4566"
  description = "Floci as the service containers see it on the runtime network; `bb estate:deploy` passes the workspace's own container names."
}

variable "service_a_url" {
  type    = string
  default = "http://deliberator-default-service-a:8081"
}

variable "db_url" {
  type    = string
  default = "jdbc:postgresql://deliberator-default-postgres:5432/deliberator"
}

variable "auto_publish" {
  type    = string
  default = "false"
}

provider "aws" {
  region                      = "eu-central-1"
  access_key                  = "test"
  secret_key                  = "test"
  skip_credentials_validation = true
  skip_metadata_api_check     = true
  skip_requesting_account_id  = true
  skip_region_validation      = true
  s3_use_path_style           = true

  endpoints {
    s3             = var.endpoint
    sqs            = var.endpoint
    dynamodb       = var.endpoint
    iam            = var.endpoint
    lambda         = var.endpoint
    sts            = var.endpoint
    events         = var.endpoint
    logs           = var.endpoint
    ssm            = var.endpoint
    secretsmanager = var.endpoint
    ecs            = var.endpoint
    cloudwatch     = var.endpoint
  }
}

# A second "environment" on the same emulator: Floci keeps state per region, so eu-west-1 is a world of its own where the
# same names hold other values. `bb estate:deploy` points the environment `local2` at it (the stand-in for a remote environment
# until M4, for `service.start --secrets-from local2`).
provider "aws" {
  alias                       = "local2"
  region                      = "eu-west-1"
  access_key                  = "test"
  secret_key                  = "test"
  skip_credentials_validation = true
  skip_metadata_api_check     = true
  skip_requesting_account_id  = true
  skip_region_validation      = true

  endpoints {
    ssm            = var.endpoint
    secretsmanager = var.endpoint
  }
}

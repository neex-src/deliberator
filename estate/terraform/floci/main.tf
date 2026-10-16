terraform {
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.0" }
  }
}

provider "aws" {
  region                      = "eu-central-1"
  access_key                  = "test"
  secret_key                  = "test"
  skip_credentials_validation = true
  skip_metadata_api_check     = true
  skip_requesting_account_id  = true
  s3_use_path_style           = true

  endpoints {
    s3             = "http://localhost:4566"
    sqs            = "http://localhost:4566"
    dynamodb       = "http://localhost:4566"
    iam            = "http://localhost:4566"
    lambda         = "http://localhost:4566"
    sts            = "http://localhost:4566"
    events         = "http://localhost:4566"
    logs           = "http://localhost:4566"
    ssm            = "http://localhost:4566"
    secretsmanager = "http://localhost:4566"
    ecs            = "http://localhost:4566"
    cloudwatch     = "http://localhost:4566"
  }
}

resource "aws_s3_bucket" "hello" {
  bucket = "a sandbox AWS account-hello"
}

resource "aws_sqs_queue" "hello" {
  name = "a sandbox AWS account-hello"
}

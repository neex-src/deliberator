terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

provider "aws" {
  region = "eu-central-1"
}

resource "aws_sqs_queue" "plain" {
  name = "svy-unsupported-plain"
}

# Floci has no X-Ray: UnknownOperationException
resource "aws_xray_group" "slow" {
  group_name        = "svy-slow"
  filter_expression = "responsetime > 5"
}

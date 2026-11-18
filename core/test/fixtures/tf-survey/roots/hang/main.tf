terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    external = {
      source = "hashicorp/external"
    }
  }
}

provider "aws" {
  region = "eu-central-1"
}

# a lookup that never answers
data "external" "slow" {
  program = ["sleep", "600"]
}

resource "aws_sqs_queue" "plain" {
  name = "svy-hang-plain"
}

resource "aws_sqs_queue" "after_slow" {
  name = "svy-hang-${data.external.slow.result.name}"
}

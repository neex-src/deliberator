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

# the VPC of the real account: found by its tag, which only exists there
data "aws_vpc" "main" {
  tags = {
    Name = "main-vpc"
  }
}

resource "aws_sqs_queue" "plain" {
  name = "svy-lookup-plain"
}

resource "aws_sqs_queue" "per_vpc" {
  name = "svy-lookup-${data.aws_vpc.main.id}"
}

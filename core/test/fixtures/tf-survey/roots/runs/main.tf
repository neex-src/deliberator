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

variable "prefix" {
  default = "svy-runs"
}

resource "aws_sqs_queue" "inbox" {
  name = "${var.prefix}-inbox"
}

resource "aws_ssm_parameter" "queue_url" {
  name  = "/${var.prefix}/queue-url"
  type  = "String"
  value = aws_sqs_queue.inbox.url
}

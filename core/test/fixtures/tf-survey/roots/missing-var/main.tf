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

# per-environment values live in a tfvars file that is not loaded here
variable "environment" {
  type = string
}

variable "retention_days" {
  type = number
}

resource "aws_sqs_queue" "inbox" {
  name = "svy-${var.environment}-inbox"
}

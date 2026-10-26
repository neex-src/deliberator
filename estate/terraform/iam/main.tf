# The shared IAM root of the estate: a role the application roots look up by name (`data "aws_iam_role"` in ../aws), so the
# two roots have an order: this one is applied first (`dlb terraform apply-all` finds that from the .tf files, M6.3).
# Like ../aws it runs on Floci only through `terraform.local` (the override file) or on a real account with a profile.
terraform {
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 6.0" }
  }
}

provider "aws" {
  region = "eu-central-1"
}

resource "aws_iam_role" "shared" {
  name = "estate-shared"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "lambda.amazonaws.com" }
    }]
  })
}

output "role_arn" { value = aws_iam_role.shared.arn }

#!/usr/bin/env node
import "source-map-support/register";
import * as cdk from "aws-cdk-lib";
import { DocVaultStorageStack } from "../lib/docvault-storage-stack";

const app = new cdk.App();

// Pull environment from CDK context: cdk deploy -c env=dev
const environment = app.node.tryGetContext("env") || "dev";

new DocVaultStorageStack(app, `DocVaultStorage-${environment}`, {
  environment,
  // Override in cdk.json or via -c bucketName=... if you want a custom name
  bucketName: app.node.tryGetContext("bucketName"),
  env: {
    account: process.env.CDK_DEFAULT_ACCOUNT,
    region: process.env.CDK_DEFAULT_REGION || "us-east-1",
  },
});

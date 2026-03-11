import * as cdk from "aws-cdk-lib";
import * as s3 from "aws-cdk-lib/aws-s3";
import * as iam from "aws-cdk-lib/aws-iam";
import { Construct } from "constructs";

interface DocVaultStorageStackProps extends cdk.StackProps {
  environment: string;
  bucketName?: string;
}

export class DocVaultStorageStack extends cdk.Stack {
  public readonly bucket: s3.Bucket;
  public readonly appUser: iam.User;

  constructor(scope: Construct, id: string, props: DocVaultStorageStackProps) {
    super(scope, id, props);

    const { environment } = props;

    // ========================================================================
    // S3 BUCKET — private, encrypted, lifecycle-managed
    // ========================================================================
    this.bucket = new s3.Bucket(this, "DocVaultFilesBucket", {
      // If no custom name provided, CDK auto-generates a unique one (recommended).
      // Custom names must be globally unique across all AWS accounts.
      bucketName: props.bucketName,

      // Block all public access — files served via pre-signed URLs only
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,

      // Server-side encryption with S3-managed keys (free tier friendly)
      encryption: s3.BucketEncryption.S3_MANAGED,

      // Versioning off for now — keeps storage costs minimal on free tier.
      // Turn on later if you want file version history.
      versioned: false,

      // Enforce that every request uses the bucket owner's credentials for access.
      // Disables ACLs entirely — all access controlled via IAM policies + bucket policy.
      objectOwnership: s3.ObjectOwnership.BUCKET_OWNER_ENFORCED,

      // CORS — needed for pre-signed URL uploads from a browser later
      cors: [
        {
          allowedMethods: [
            s3.HttpMethods.GET,
            s3.HttpMethods.PUT,
            s3.HttpMethods.POST,
            s3.HttpMethods.DELETE,
            s3.HttpMethods.HEAD,
          ],
          allowedOrigins: environment === "prod"
            ? ["https://yourdomain.com"]  // Lock down in prod
            : ["http://localhost:3000"],   // React dev server
          allowedHeaders: ["*"],
          exposedHeaders: [
            "ETag",
            "x-amz-meta-custom-header",
            "x-amz-server-side-encryption",
          ],
          maxAge: 3600,
        },
      ],

      // Lifecycle rules — auto-clean incomplete multipart uploads.
      // These silently accumulate and cost money if left unchecked.
      lifecycleRules: [
        {
          id: "abort-incomplete-multipart",
          abortIncompleteMultipartUploadAfter: cdk.Duration.days(7),
          enabled: true,
        },
      ],

      // For dev: destroy bucket when stack is torn down (cdk destroy).
      // For prod: you'd change this to RETAIN.
      removalPolicy: environment === "prod"
        ? cdk.RemovalPolicy.RETAIN
        : cdk.RemovalPolicy.DESTROY,
      autoDeleteObjects: environment !== "prod",
    });

    // ========================================================================
    // IAM USER — least-privilege access for the Spring Boot backend
    // ========================================================================
    this.appUser = new iam.User(this, "DocVaultAppUser", {
      userName: `docvault-app-${environment}`,
    });

    // Access key for the app — credentials go into Spring Boot's env vars
    const accessKey = new iam.AccessKey(this, "DocVaultAppAccessKey", {
      user: this.appUser,
    });

    // Scoped-down policy — ONLY what the backend needs, nothing more.
    // No ListAllMyBuckets, no DeleteBucket, no policy changes.
    this.bucket.addToResourcePolicy(
      new iam.PolicyStatement({
        effect: iam.Effect.ALLOW,
        principals: [this.appUser],
        actions: [
          "s3:PutObject",       // Upload files
          "s3:GetObject",       // Download / HEAD files / pre-signed GET URLs
          "s3:DeleteObject",    // Delete files
        ],
        resources: [this.bucket.arnForObjects("*")],
      })
    );

    // ListBucket is a bucket-level permission (not object-level)
    // Needed for listing files by prefix (e.g., user's files)
    this.bucket.addToResourcePolicy(
      new iam.PolicyStatement({
        effect: iam.Effect.ALLOW,
        principals: [this.appUser],
        actions: ["s3:ListBucket"],
        resources: [this.bucket.bucketArn],
      })
    );

    // Also attach as an inline policy on the user (belt + suspenders)
    this.appUser.addToPolicy(
      new iam.PolicyStatement({
        effect: iam.Effect.ALLOW,
        actions: [
          "s3:PutObject",
          "s3:GetObject",
          "s3:DeleteObject",
        ],
        resources: [this.bucket.arnForObjects("*")],
      })
    );

    this.appUser.addToPolicy(
      new iam.PolicyStatement({
        effect: iam.Effect.ALLOW,
        actions: ["s3:ListBucket"],
        resources: [this.bucket.bucketArn],
      })
    );

    // ========================================================================
    // OUTPUTS — values you'll paste into your Spring Boot .env / application.yaml
    // ========================================================================
    new cdk.CfnOutput(this, "BucketName", {
      value: this.bucket.bucketName,
      description: "S3 bucket name → app.aws.s3.bucket-name",
    });

    new cdk.CfnOutput(this, "BucketArn", {
      value: this.bucket.bucketArn,
      description: "S3 bucket ARN (for reference)",
    });

    new cdk.CfnOutput(this, "Region", {
      value: this.region,
      description: "AWS region → app.aws.region",
    });

    new cdk.CfnOutput(this, "AccessKeyId", {
      value: accessKey.accessKeyId,
      description: "IAM access key ID → AWS_ACCESS_KEY_ID env var",
    });

    new cdk.CfnOutput(this, "SecretAccessKey", {
      value: accessKey.secretAccessKey.unsafeUnwrap(),
      description: "IAM secret key → AWS_SECRET_ACCESS_KEY env var (SAVE THIS — shown only once in deploy output)",
    });
  }
}

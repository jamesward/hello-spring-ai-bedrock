Hello Spring AI Bedrock
-----------------------

1. Open IAM Users Dashboard [https://us-east-1.console.aws.amazon.com/iam/home?region=us-east-1#/users]
2. Click create user and fill in fields specifying "programmatic access"
3. Select "Attach policies directly" and search for the "AmazonBedrockFullAccess" policy
4. Finish creating the user
5. Select the new user
6. Select "Security credentials"
7. Under Access Keys, select to create a new Access Key
8. Select Local Code as the use
9. Create the access key
10. Set the env vars:
    ```
    export SPRING_AI_BEDROCK_AWS_ACCESS_KEY=YOURS
    export SPRING_AI_BEDROCK_AWS_SECRET_KEY=YOURS
    ```
11. Enable model access for Nova Lite in `us-east-1` [https://us-east-1.console.aws.amazon.com/bedrock/home?region=us-east-1#/modelaccess]
12. Run the app: `./gradlew bootRun`

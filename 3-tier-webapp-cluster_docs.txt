eksctl create cluster --name my-cluster --region ap-south-1 --version 1.28 --vpc-public-subnets subnet-02d3fcd1f97d0769b,subnet-0840a589fde007d28 --node-ami-family Ubuntu2004 --node-type t2.medium --nodes 3 --ssh-access --ssh-public-key newKey2




---------------------------------------------------------------------------------------------------------------------------------------------------------
|									Database POD									|
---------------------------------------------------------------------------------------------------------------------------------------------------------
YAML file to deploy POD for Database, a service for database and keep service type as clusterIp, so that nodes inside the cluster should be accessible to this pod
# database-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: database
spec:
  replicas: 1
  selector:
    matchLabels:
      app: database
  template:
    metadata:
      labels:
        app: database
    spec:
      containers:
      - name: database
        image: chaitanyachay/database-image:latest
        ports:
        - containerPort: 3306
---
# database-service.yaml
apiVersion: v1
kind: Service
metadata:
  name: database
spec:
  selector:
    app: database
  ports:
    - protocol: TCP
      port: 3306			# Containers port exposed
      targetPort: 3306			# Pods port


> kubectl apply -f database-deployment.yml

---------------------------------------------------------------------------------------------------------------------------------------------------------
|									Backend POD									|
---------------------------------------------------------------------------------------------------------------------------------------------------------
YAML File to deploy POD for backend, and a service for backend and keep service type as clusterIp, so that nodes inside the cluster should be accessible to this pod

apiVersion: apps/v1
kind: Deployment
metadata:
  name: backend
spec:
  replicas: 2
  selector:
    matchLabels:
      app: backend
  template:
    metadata:
      labels:
        app: backend
    spec:
      containers:
      - name: backend
        image: chaitanyachay/backend-image:latest
        ports:
        - containerPort: 8000
        env:
        - name: DATABASE_HOST
          value: "database"
---
# backend-service.yaml
apiVersion: v1
kind: Service
metadata:
  name: backend
spec:
  selector:
    app: backend
  ports:
    - protocol: TCP
      port: 8000
      targetPort: 8000

> kubectl apply -f backend-deployment.yml

---------------------------------------------------------------------------------------------------------------------------------------------------------
|									Frontend POD|									|
---------------------------------------------------------------------------------------------------------------------------------------------------------
YAML File to deploy POD for frontend, and a service for frontend and keep service type as clusterIp, so that nodes inside the cluster should be accessible to this pod

apiVersion: apps/v1
kind: Deployment
metadata:
  name: frontend
spec:
  replicas: 2
  selector:
    matchLabels:
      app: frontend
  template:
    metadata:
      labels:
        app: frontend
    spec:
      containers:
        - name: frontend
          image: chaitanyachay/frontend-image:latest
          ports:
            - containerPort: 80
          env:
            - name: BACKEND_HOST
              value: "backend"
---
# frontend-service.yaml
apiVersion: v1
kind: Service
metadata:
  name: frontend
spec:
  selector:
    app: frontend
  ports:
    - protocol: TCP
      port: 80
      targetPort: 80
  type: NodePort

> kubectl apply -f frontend-deployment.yml

-------------------------------------------------------------------------------------------------------------------------------------------------
|									Ingress									|
-------------------------------------------------------------------------------------------------------------------------------------------------
Ingress file for our application
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: ingress-01  
  annotations:
    kubernetes.io/ingress.class: alb
    alb.ingress.kubernetes.io/scheme: internet-facing
    alb.ingress.kubernetes.io/target-type: ip
    alb.ingress.kubernetes.io/subnets: subnet-014bc3fec6b2cd8e8, subnet-0840a589fde007d28, subnet-02d3fcd1f97d0769b
spec:
  ingressClassName: alb
  rules:
    - http:
        paths:
        - path: /*
          pathType: Prefix
          backend:
            service:
              name: frontend
              port:
                number: 80

> kubectl apply -f ingress.yml
> kubectl get ingress
-------------------------------------------------------------------------------------------------------------------------------------------------
|								Ingress Controller								|
-------------------------------------------------------------------------------------------------------------------------------------------------
Now we have to deploy AWS Load Balancer Controller (Ingress controller to an Amazon EKS cluster):
1. Create an IAM policy
	- Download an IAM policy for the AWS Load Balancer Controller that allows it to make calls to AWS APIs on your behalf.
	> curl -O https://raw.githubusercontent.com/kubernetes-sigs/aws-load-balancer-controller/v2.5.4/docs/install/iam_policy.json
	
	- Create an IAM policy using the policy downloaded
	> aws iam create-policy --policy-name AWSLoadBalancerControllerIAMPolicy --policy-document file://iam_policy.json

2. Create a OIDC provider
	> export cluster_name=my-cluster
	> oidc_id=$(aws eks describe-cluster --name $cluster_name --query "cluster.identity.oidc.issuer" --output text | cut -d '/' -f 5)
	
	- Check if there is an IAM OIDC provider configured already
	> aws iam list-open-id-connect-providers | grep $oidc_id | cut -d "/" -f4
	
	- If not, run the below command
	> eksctl utils associate-iam-oidc-provider --cluster $cluster_name --approve

3. Create an IAM role
	- Create a Kubernetes service account named aws-load-balancer-controller for the AWS Load Balancer Controller and annotate the Kubernetes service account with the name of the IAM role.
	> eksctl create iamserviceaccount \
  --cluster=my-cluster \
  --namespace=default \
  --name=aws-load-balancer-controller \
  --role-name AmazonEKSLoadBalancerControllerRole \
  --attach-policy-arn=arn:aws:iam::292152202882:policy/AWSLoadBalancerControllerIAMPolicy \
  --approve

4. Install the AWS Load Balancer Controller (using helm)
	- Add the eks-charts repository.
	> helm repo add eks https://aws.github.io/eks-charts
	
	- Update your local repo to make sure that you have the most recent charts
	> helm repo update eks
	
	- Install the AWS Load Balancer Controller.
	> helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n default \
  --set clusterName=my-cluster \
  --set serviceAccount.create=false \
  --set serviceAccount.name=aws-load-balancer-controller \
  --set region=ap-south-1 \
  --set vpcId=vpc-086f4b8d51a4b80e7
	
	- Verify the controller is installed
	> kubectl get deployment aws-load-balancer-controller

	
	Note: While create aws-load-balancer-controller, we got error due to subnet, so I mentioned the subnets directly in ingress.yml file in annotations




Troubleshoot:
	> kubectl edit deploy/aws-load-balancer-controller
	check for status





 
-----------------------------------------------------------------------------------------------------------------------------------------------
Documentation link: https://docs.aws.amazon.com/eks/latest/userguide/aws-load-balancer-controller.html
-----------------------------------------------------------------------------------------------------------------------------------------------



-------------------------------------------------------------------------------------------------------------------------------------------------
|							Configuring Horizontal POD Autoscaler							|
-------------------------------------------------------------------------------------------------------------------------------------------------

We need a metric server to get metrics of the pods, for that we create a metric pod as:
> wget -O metricserver.yml https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml

Now we need to edit some part in the metricserver.yml file
	In "kind: Deployment" part
	Under
		Container
		- args:
	Add following:
		- --kubelet-insecure-tls

Now apply the metricserver.yml file
> kubectl apply -f metricserver.yml

Also you need to add resources to you backend yaml file and apply it
	- add the following things
		 resources:
                   limits:
                     cpu: 500m
                   requests:
                     cpu: 200m

Now create an HorizontalPodAutoscaler for you pod
> kubectl autoscale deployment backend --cpu-percent=20 --min=1 --max=10

Duplicate your instance
	- On 1 instance run "watch kubctl get all" to see the update of cluster every second
	- And on second instance login to a container, and manually increase load and see the autoscaling live in 1st terminal





-------------------------------------------------------------------------------------------------------------------------------------------------
|							Configuring Vertical POD Autoscaler							|
-------------------------------------------------------------------------------------------------------------------------------------------------

To deploy the Vertical Pod Autoscaler:
	-  Download the Vertical Pod Autoscaler source code.
	> git clone https://github.com/kubernetes/autoscaler.git
	> cd autoscaler/vertical-pod-autoscaler/

	- Deploy the Vertical Pod Autoscaler to your cluster with the following command.
	> ./hack/vpa-up.sh
	
	- Verify that the Vertical Pod Autoscaler Pods have been created successfully.	
	> kubectl get pods -n kube-system

Now to test the VPA,
	- add following resource quota to the deployment group on which you want to apply the VPA (here I am taking frontend deployment group)
	resources:
            requests:
              cpu: 100m
              memory: 50Mi
	
	- Apply the changes
	> kubectl apply -y frontend-deployment.yml
	
	Now create a yml file to make VerticalPodAutoscaler:
		apiVersion: "autoscaling.k8s.io/v1"
		kind: VerticalPodAutoscaler
		metadata:
		  name: hamster-vpa
		spec:
		  targetRef:
		    kind: Deployment
		    name: frontend
		  resourcePolicy:
		    containerPolicies:
		      - containerName: '*'
		        minAllowed:
		          cpu: 100m
		          memory: 50Mi
		        maxAllowed:
		          cpu: 500m
		          memory: 500Mi
		        controlledResources: ["cpu", "memory"]
	> kubectl apply -f frontendVPA.yml


Describe one of the pod and see the CPU and Memory, Now login in any of the pod and manually apply the load, when you describe the pods again, you will see that the CPU or Memory will be changed














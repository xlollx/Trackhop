# Trackhop on Azure

[![Deploy to Azure](https://aka.ms/deploytoazurebutton)](https://portal.azure.com/#create/Microsoft.Template/uri/https%3A%2F%2Fraw.githubusercontent.com%2Fxlollx%2FTrackhop%2Fmain%2Fpackaging%2Fazure%2Fazuredeploy.json)

`azuredeploy.json` creates, in the resource group you choose:

- a **Storage Account** (Standard LRS) with the file share `trackhop-data`, mounted as `/data`;
- a **Container Apps environment** (Consumption plan, no log workspace);
- the **Container App** `trackhop`: `ghcr.io/xlollx/trackhop`, 0.25 vCPU and 0.5 GiB, always one replica
  (the scheduler has to stay on), HTTPS on an `*.azurecontainerapps.io` address.

It asks for a name, a region, a user name and a password, and whether several people will use it.
`SONGPORT_URL` is set to the app's HTTPS address, which the services' sign-in pages need.
The address is shown under *Outputs* when the deployment ends.

Costs go to your Azure subscription: check the [pricing calculator](https://azure.microsoft.com/pricing/calculator/)
for Container Apps and Azure Files in your region. To update, restart the revision (the `latest` image is pulled
again); to remove everything, delete the resource group.

From a datacenter, the unofficial connectors (web players) are blocked more often: use the official ones.

#! /bin/bash

# copy all the deployment files over to the beast...
scp out/artifacts/CPO.jar tom@beast:/apps/cpo
scp CPO.service tom@beast:/apps/cpo
scp logging.properties tom@beast:/apps/cpo
scp configurationCPO.java tom@beast:/apps/cpo

# execute commands on the beast
# get to the app directory
# set mode and owner on files that stay in that directory
# copy the service wrapper (if it has changed) to the systemd/system directory, change its mode and owner
# bounce the CPO service
ssh tom@beast << RUN_ON_BEAST
cd /apps/cpo
sudo chown cpo:cpo CPO.jar
sudo chmod ug+xrw CPO.jar
sudo chown cpo:cpo logging.properties
sudo chmod ug+xrw logging.properties
sudo chown cpo:cpo configurationCPO.java
sudo chmod ug+xrw configurationCPO.java
sudo cp -u CPO.service /etc/systemd/system
sudo chown cpo:cpo /etc/systemd/system/CPO.service
sudo chmod ug+xrw /etc/systemd/system/CPO.service
sudo systemctl stop CPO.service
sudo systemctl daemon-reload
sudo systemctl start CPO.service
RUN_ON_BEAST

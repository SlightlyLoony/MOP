#! /bin/bash

# copy all the deployment files over to the beast...
scp out/artifacts/CPO.jar tom@beast:/apps/cpo
scp CPO.service tom@beast:/apps/cpo
scp CPOConfigTom.json tom@beast:/apps/cpo
scp CPOLog.json tom@beast:/apps/cpo
scp runCPO.sh tom@beast:/apps/cpo

# execute commands on the beast
# get to the app directory
# set mode and owner on files that stay in that directory
# copy the service wrapper (if it has changed) to the systemd/system directory, change its mode and owner
# bounce the CPO service
ssh tom@beast << RUN_ON_BEAST
cd /apps/cpo
sudo chown cpo:cpo CPO.jar
sudo chmod ug+xrw CPO.jar
sudo chown cpo:cpo CPOConfigTom.json
sudo chmod ug+xrw CPOConfigTom.json
sudo chown cpo:cpo CPOLog.json
sudo chmod ug+xrw CPOLog.json
sudo chown cpo:cpo runCPO.sh
sudo chmod ug+xrw runCPO.sh
sudo cp -u CPO.service /etc/systemd/system
sudo chown cpo:cpo /etc/systemd/system/CPO.service
sudo chmod ug+xrw /etc/systemd/system/CPO.service
sudo systemctl stop CPO.service
sudo systemctl daemon-reload
sudo systemctl start CPO.service
RUN_ON_BEAST

# Getting Started ##

This documentation intends to explain how to get penpot application and run it locally.

The simplest approach is using docker and docker-compose. 

## Install Docker ##

Skip this section if you alreasdy have docker installed, up and running.

You can install docker and its dependencies from your distribution
repositores with:

```bash
sudo apt-get install docker docker-compose
```

Or follow installation instructions from docker.com; (for debian
https://docs.docker.com/engine/install/debian/).

Ensure that the docker is started and optionally enable it to start
with the system:

```bash
sudo systemctl start docker
sudo systemctl enable docker
```

And finally, add your user to the docker group:

```basb
sudo usermod -aG docker $USER
```

This will make use the docker without `sudo` command all the time.

NOTE: probably you will need to relogin again to make this change
take effect.


## Start penpot application ##

You can create it from scratch or take a base from the [penpot
repository][1]

[1]: https://raw.githubusercontent.com/penpot/penpot/develop/docker/images/docker-compose.yaml

```bash
wget https://raw.githubusercontent.com/penpot/penpot/develop/docker/images/docker-compose.yaml
```

And then:

```bash
docker-compose -p penpotest -f docker-compose.yaml up
```

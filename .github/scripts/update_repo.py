import json
import os

data = {
    "name": "nonton-indo",
    "description": "CloudStream 3 Extensions untuk Situs Streaming Indonesia",
    "manifestVersion": 1,
    "pluginLists": [
        "https://raw.githubusercontent.com/ExtremeBoyGG/nonton-indo/builds/plugins.json"
    ]
}

with open('repo.json', 'w') as f:
    json.dump(data, f, indent=2)
print('repo.json updated')

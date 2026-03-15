import json
import os

data = json.load(open('repo.json')) if os.path.exists('repo.json') else []

new_entry = {
    'name': 'nonton-indo',
    'repositories': ['https://raw.githubusercontent.com/ExtremeBoyGG/nonton-indo/builds/plugins.json']
}

# Filter out invalid entries (strings, None, dll) dan entry lama dengan nama sama
data = [
    entry for entry in data
    if isinstance(entry, dict) and entry.get('name') != 'nonton-indo'
]

data.append(new_entry)

with open('repo.json', 'w') as f:
    json.dump(data, f, indent=2)
print('repo.json updated')

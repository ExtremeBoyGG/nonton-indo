import json
import os

data = json.load(open('repo.json')) if os.path.exists('repo.json') else []

new_entry = {
    'name': 'nonton-indo',
    'repositories': ['https://raw.githubusercontent.com/ExtremeBoyGG/nonton-indo/builds/plugins.json']
}

found = False
for i, entry in enumerate(data):
    if entry.get('name') == 'nonton-indo':
        data[i] = new_entry
        found = True
        break
if not found:
    data.append(new_entry)

with open('repo.json', 'w') as f:
    json.dump(data, f, indent=2)
print('repo.json updated')

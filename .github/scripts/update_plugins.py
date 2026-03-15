import json
import os

if os.path.exists('plugins.json'):
    with open('plugins.json', 'r') as f:
        data = json.load(f)
else:
    data = []

existing = {entry.get('name'): entry for entry in data if isinstance(entry, dict)}

# Domain mapping untuk iconUrl yang benar (tanpa https://)
domain_map = {
    'AnimeIndo':  'anime-indo.lol',
    'Oploverz':   'oploverz.ch',
    'Otakudesu':  'otakudesu.blog',
    'Nimegami':   'nimegami.id',
    'Animasu':    'animasuid.com',
    'Pahe':       'pahe.ink',
    'Rebahin':    'rebahin.com',
    'Idlix':      'idlix.com',
}

for cs3_file in os.listdir('.'):
    if cs3_file.endswith('.cs3'):
        name = cs3_file[:-4]
        filepath = os.path.join('.', cs3_file)
        try:
            size = os.path.getsize(filepath)
            domain = domain_map.get(name, name.lower() + '.com')

            if name in existing:
                existing[name]['fileSize'] = size
                existing[name]['downloadCount'] = existing[name].get('downloadCount', 0)
                # Fix URL dan iconUrl yang lama jika salah
                existing[name]['url'] = f'https://raw.githubusercontent.com/ExtremeBoyGG/nonton-indo/builds/{cs3_file}'
                existing[name]['iconUrl'] = f'https://www.google.com/s2/favicons?domain={domain}&sz=%size%'
            else:
                new_entry = {
                    'name': name,
                    'internalName': name,
                    'version': 1,
                    'apiVersion': 1,
                    'repositoryUrl': 'https://github.com/ExtremeBoyGG/nonton-indo',
                    'fileSize': size,
                    'downloadCount': 0,
                    'status': 1,
                    'language': 'id',
                    'authors': ['ExtremeBoy'],
                    'url': f'https://raw.githubusercontent.com/ExtremeBoyGG/nonton-indo/builds/{cs3_file}',
                    'iconUrl': f'https://www.google.com/s2/favicons?domain={domain}&sz=%size%'
                }
                if name in ['AnimeIndo', 'Oploverz', 'Otakudesu', 'Nimegami', 'Animasu']:
                    new_entry['tvTypes'] = ['Anime', 'AnimeMovie', 'OVA']
                else:
                    new_entry['tvTypes'] = ['Movie', 'TvSeries']
                data.append(new_entry)

            print(f'Updated {name}: {size} bytes')
        except Exception as e:
            print(f'Error processing {cs3_file}: {e}')

with open('plugins.json', 'w') as f:
    json.dump(data, f, indent=2, ensure_ascii=False)
print('plugins.json updated')

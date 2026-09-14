"""Inspect the release archive without a Minecraft server or third-party Python packages."""

import collections
import hashlib
import json
from pathlib import Path
import sys
import zipfile

jar = Path(sys.argv[1])
with zipfile.ZipFile(jar) as archive:
    names = archive.namelist()
    duplicates = [name for name, count in collections.Counter(names).items() if count > 1]
    assert not duplicates, f"Duplicate archive entries: {duplicates}"
    required = [
        'plugin.yml', 'config.yml', 'commands.yml', 'permissions.yml', 'prices.yml',
        'gui.yml', 'storage.yml', 'integrations.yml', 'lang/en_US.yml',
        'categories.yml', 'progression.yml', 'progress-gui.yml', 'containers.yml',
        'pricing.yml', 'custom-items.yml', 'worth-lore.yml',
        'META-INF/wellsell/LICENSE', 'META-INF/wellsell/THIRD_PARTY_NOTICES.md',
        'META-INF/wellsell/licenses/mariadb-LGPL-2.1.txt',
        'com/wellsetups/WellSell/Main.class',
    ]
    assert all(name in names for name in required), 'A required resource is missing'
    forbidden = (
        'org/bukkit/', 'net/milkbowl/vault/', 'me/clip/placeholderapi/', 'net/minecraft/',
        'net/kyori/', 'org/yaml/snakeyaml/', 'org/bstats/', 'org/sqlite/', 'org/mariadb/',
        'com/github/retrooper/', 'io/github/retrooper/', 'io/netty/',
        'net/brcdev/shopgui/', 'me/gypopo/economyshopgui/',
    )
    assert not any(name.startswith(forbidden) for name in names), 'Unexpected unrelocated/server API'
    versions = collections.Counter()
    for name in names:
        if (name.endswith('.class') and not name.startswith('META-INF/versions/')
                and not name.endswith('module-info.class')):
            major = int.from_bytes(archive.read(name)[6:8], 'big')
            versions[major] += 1
            assert major <= 61, f'Java 17 incompatible base class: {name} ({major})'
    metadata = archive.read('plugin.yml').decode('utf-8')
    assert 'main: com.wellsetups.WellSell.Main' in metadata
    assert "api-version: '1.20'" in metadata
    assert '${version}' not in metadata
    assert 'org.xerial:sqlite-jdbc:3.49.1.0' in metadata
    assert 'org.mariadb.jdbc:mariadb-java-client:3.5.3' in metadata
    assert jar.stat().st_size < 2_000_000, 'Unexpected runtime size regression'
    print(json.dumps({
        'jar': jar.name,
        'sha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
        'bytes': jar.stat().st_size,
        'baseClassMajorVersions': dict(versions),
        'duplicateEntries': len(duplicates),
        'requiredResourcesPresent': True,
        'serverAndOptionalPluginApisBundled': False,
    }, indent=2))

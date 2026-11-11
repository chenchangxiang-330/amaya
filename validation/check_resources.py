from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path('app/src/main/res')
en_root = ET.parse(root / 'values/strings.xml').getroot()
zh_root = ET.parse(root / 'values-zh-rCN/strings.xml').getroot()
en = {item.attrib['name']: item for item in en_root}
zh = {item.attrib['name']: item for item in zh_root}
assert len(en) == len(en_root), 'Duplicate English resource names'
assert len(zh) == len(zh_root), 'Duplicate Chinese resource names'
expected = {key for key, value in en.items() if value.get('translatable') != 'false'}
assert set(zh) == expected, (expected - set(zh), set(zh) - expected)
for key, value in zh.items():
    original = en[key]
    assert original.tag == value.tag, key
    source_items = original.findall('item') if original.tag == 'plurals' else [original]
    target_items = value.findall('item') if value.tag == 'plurals' else [value]
    formats = lambda node: sorted(set(re.findall(r'%\d+\$[sdf]', ''.join(node.itertext()))))
    assert all(formats(node) == formats(source_items[-1]) for node in source_items + target_items), key
    assert ''.join(value.itertext()).strip(), key
    if value.tag == 'plurals':
        assert {item.get('quantity') for item in target_items} == {'other'}, key
assert not Path('app/src/main/java/com/amaya/intelligence/ui/res/UiStrings.kt').exists()
print(f'PASS: {len(expected)} translated string/plural resources, identical argument types and indices; English fallback and nontranslatable names present.')

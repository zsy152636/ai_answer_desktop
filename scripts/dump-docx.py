import sys, docx
src, dst = sys.argv[1], sys.argv[2]
d = docx.Document(src)
out = []
for el in d.element.body.iter():
    tag = el.tag.split('}')[-1]
    if tag == 'p':
        out.append('\n')
    elif tag == 't':
        out.append(el.text or '')
    elif tag == 'tbl':
        out.append('\n[TABLE]\n')
with open(dst, 'w', encoding='utf-8') as f:
    f.write(''.join(out))
print('paragraphs:', len(d.paragraphs), 'tables:', len(d.tables))

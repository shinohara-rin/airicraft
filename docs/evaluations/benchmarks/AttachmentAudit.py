"""Audit recorded leaf attachment capabilities and selected torch supports in the leaf-wall fixture."""
import gzip,json,re,sys
known={}; leaves=set(); placements=[]
with gzip.open(sys.argv[1],'rt') as stream:
 for line in stream:
  row=json.loads(line)
  if row.get('type')!='turn': continue
  o=row.get('observation')
  if o is None:known.clear();continue
  for p in o['removed']:known.pop(tuple(p[k] for k in ('x','y','z')),None)
  for cell in o['changed']:
   key=tuple(cell['pos'][k] for k in ('x','y','z'));v=cell['seen'];known[key]=v
   if v['identified'] and v['blockId'].endswith('_leaves'):
    assert v['attachment']=={'fullFaces':[],'centerUp':False},v
    leaves.add(key)
  for effect in row['effects']:
   m=re.search(r'command=Place\[item=minecraft:torch, support=Pos\[x=(-?\d+), y=(-?\d+), z=(-?\d+)\], expectedSupport=([^,]+), face=(\w+),',effect)
   if not m:continue
   pos=tuple(int(m[i]) for i in (1,2,3));v=known[pos];face=m[5]
   assert v['identified'] and v['blockId']==m[4]
   assert v['attachment']['centerUp'] if face=='UP' else face in v['attachment']['fullFaces']
   placements.append({'tick':row['tick'],'support':pos,'block':m[4],'face':face})
assert leaves and placements,'Missing leaf and placement evidence'
print(json.dumps({'identifiedLeafCells':len(leaves),'placements':placements,'scope':'Recorded attachment capabilities and selected supports; no full hidden-read audit.'}))

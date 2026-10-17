"""Optional host regression corpus. Requires pefile, unicorn and test-tak1.
Generated audio contains simple synthetic integer patterns, not music.
"""
from pathlib import Path
import sys,struct,subprocess
from tak1_reference import Oracle
ORDERS=[4,8,12,16,24,32,48,64,80,96,112,128,160,192,224,256]
def boundaries(rate,samples,alignment,divider,maximum):
 out=[0]
 for i in range(1,maximum+1):
  out.append((rate*i//divider+alignment//2)&-alignment)
  if out[-1]>=samples:break
 if out[-1]<samples:out[-1]=samples
 elif out[-1]>samples:
  out[-1]=samples
  if out[-1]-out[-2]<alignment:out.pop(-2)
 return out
class Writer:
 def __init__(self):self.a=[]
 def put(self,v,n):self.a.extend((v>>i)&1 for i in range(n))
 def align(self):self.a.extend([0]*((-len(self.a))&7))
 def data(self):
  self.align();b=bytearray(len(self.a)//8)
  for i,v in enumerate(self.a):b[i//8]|=v<<(i&7)
  return bytes(b)
def crc(b):
 c=0xb704ce
 for v in b:
  c^=v<<16
  for _ in range(8):c=((c<<1)^(0x864cfb if c&0x800000 else 0))&0xffffff
 return c.to_bytes(3,'little')
def checked(b):return b+crc(b)
def info(w,rate,bps,ch,n):
 for v,b in ((0,6),(2,4),(7,4),(n,32),(0,3),(0,3),(rate-6000,18),(bps-8,5),(ch-1,4),(0,1)):w.put(v,b)
def coefficients(w,c,q=0,diff=False):
 w.put(q,3)
 if len(c)>=12:w.put(diff,1)
 if diff:
  d=[];last=0
  for v in c:d.append(v+last);last=v
  c=d
 if len(c)>16:w.put(14,4);w.put(0,1)
 for i in range(0,len(c),4):
  if len(c)<=16:w.put(14,4)
  else:w.put(0,2)
  for v in c[i:i+4]:w.put(v,14)
def residues(w,values):
 w.put(0,1);w.put(1,6);w.put(24,5)
 for v in values:
  if v==0:w.put(1,1)
  else:
   w.put(0,1);a=abs(v)
   if a<=4:w.put(0,a-1);w.put(1,1);w.put(v<0,1)
   else:w.put(0,4);w.put((a-5)+(1<<23 if v<0 else 0),24)
def subframe(w,values,order=0,q=0,shift=0,lpc=0):
 w.put(bool(order),1)
 if not order:
  w.put(lpc,2);residues(w,values)
 else:
  w.put(shift,4);w.put(ORDERS.index(order),4);w.put(lpc,2);residues(w,values[:order]);c=[0]*order;c[-1]=64;coefficients(w,c,q,order>=12);residues(w,values[order:])
def channel(w,rate,bps,n,seed,shift=0,pf=False,order=0,q=0,lpc=0,parts=False):
 w.put(bool(shift),1)
 if shift:w.put(shift-1,4)
 w.put(seed,bps-shift)
 w.put(pf,1)
 if pf:w.put(2,4);w.put(0,2);coefficients(w,[0,0,0,64],2)
 indices=[0,1] if parts else [0];w.put(len(indices)-1,3)
 for i in indices[1:]:w.put(i,6)
 table=boundaries(rate,n,8,256,64);offsets=[table[i] for i in indices]+[n-1]
 for a,b in zip(offsets,offsets[1:]):
  values=[((i+seed)%7)-3 for i in range(b-a)]
  subframe(w,values,order,q,2,lpc)
def make(path,rate=44100,bps=16,ch=2,n=512,mode=0,shift=0,pf=False,order=0,q=0,lpc=0,parts=False):
 si=Writer();info(si,rate,bps,ch,n);stream=si.data();header=Writer();header.put(0xa0ff,16);header.put(3,3);header.put(0,21);header.put(n-1,14);header.put(0,2);info(header,rate,bps,ch,n);header.put(0,6);head=checked(header.data())
 w=Writer()
 if n<16:
  for c in range(ch):
   for i in range(n):w.put((i-3)*2+c,bps)
 else:
  if ch==2:w.put(mode,3)
  first=1 if mode in(2,4) else 0
  channel(w,rate,bps,n,first+1,shift,pf,order,q,lpc,parts)
  if mode in (1,2):
   w.put(1,1);w.put(1,1);w.put(2,4);w.put(1,2);coefficients(w,[0,0,0,1024,0,0,0,0])
  if ch==2:channel(w,rate,bps,n,2-first,shift,pf,order,q,lpc,parts)
 metadata=b'\x01'+(len(stream)+3).to_bytes(3,'little')+checked(stream)+b'\0'*4
 path.write_bytes(b'tBaK'+metadata+head+checked(w.data()))
def compare(path,output,n,bps,ch):
 x=Oracle(str(DLL));x.call(x.base+x.pe.OPTIONAL_HEADER.AddressOfEntryPoint,x.base,1,0);x.input_path=str(path);fn=x.alloc(64);x.uc.mem_write(fn,b'test.tak\0');opts=x.alloc(64);h=x.call('tak_SSD_Create_FromFile',fn,opts,0,0)
 assert x.call('tak_SSD_Valid',h)==1,('open',path,x.call('tak_SSD_State',h))
 out=x.alloc(16384*8);got=x.alloc(4);result=x.call('tak_SSD_ReadAudio',h,out,n,got)
 assert result==0 and x.dword(got)==n,('read',path,result,x.dword(got))
 reference=x.read(out,n*ch*((bps+7)//8));output.write_bytes(reference)
 x.call("tak_SSD_Destroy",h)
 for file in x.files.values():file.close()
 p=subprocess.run([str(NATIVE),str(path),str(output.with_suffix('.native'))],capture_output=True)
 assert p.returncode==0,p.stderr.decode()
 native=output.with_suffix('.native').read_bytes()
 assert native==reference,('mismatch',path,next((i,a,b)for i,(a,b)in enumerate(zip(native,reference))if a!=b))
 return len(reference)
if __name__=='__main__':
 import argparse,tempfile
 parser=argparse.ArgumentParser(description='Generate TAK 1.0 fixtures and compare native PCM against the original DLL')
 parser.add_argument('--dll',type=Path,required=True);parser.add_argument('--native',type=Path,required=True)
 args=parser.parse_args();DLL=args.dll.resolve();NATIVE=args.native.resolve()
 temporary=tempfile.TemporaryDirectory(prefix='loopdeck-tak1-cases-');folder=Path(temporary.name);cases=[]
 for rate in (8000,11025,22050,44100,48000,96000,192000):
  for bps in (8,16,24):cases.append(dict(rate=rate,bps=bps,ch=1))
 for bps in (8,16,24):
  for mode in range(6):cases.append(dict(bps=bps,mode=mode,shift=1,n=128 if bps==8 and mode==5 else 512))
 for n in (1,2,7,15,16,17,31,256):cases.append(dict(n=n))
 for order in (4,8,12,16,24,32,48,64,80,96,112,128,160,192,224,256):cases.append(dict(order=order,q=3,lpc=2))
 cases.extend([dict(pf=True),dict(pf=True,mode=1),dict(pf=True,mode=2),dict(parts=True),dict(parts=True,order=16,lpc=1)])
 total=0
 for index,case in enumerate(cases):
  p=folder/f'case-{index:03}.tak';make(p,**case)
  total+=compare(p,p.with_suffix('.reference'),case.get('n',512),case.get('bps',16),case.get('ch',2))
  print(index,'match',case,flush=True)
 print('All matched',len(cases),'cases',total,'PCM bytes')
 temporary.cleanup()

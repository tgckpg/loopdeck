"""Optional host-only reference runner for the supplied TAK decoder 2.3 DLL.

Install pefile and unicorn with pip. The Android app never uses this script,
the Windows DLL, or emulation. Only a small Win32 IO adapter is provided here;
this is not a general Windows runtime.
"""
import sys,struct
from pathlib import Path
import pefile
from unicorn import Uc,UC_ARCH_X86,UC_MODE_32,UC_HOOK_CODE,UC_HOOK_MEM_INVALID
from unicorn.x86_const import *
class Oracle:
 def __init__(self,path):
  self.pe=pefile.PE(path); self.base=self.pe.OPTIONAL_HEADER.ImageBase
  self.uc=Uc(UC_ARCH_X86,UC_MODE_32); u=self.uc
  u.mem_map(0,0x10000); u.mem_write(0,struct.pack('<I',0xffffffff))
  u.mem_map(self.base,0x30000); u.mem_write(self.base,self.pe.get_memory_mapped_image())
  u.mem_map(0x10000000,0x4000000); self.next=0x10001000
  u.mem_map(0x60000000,0x100000); self.stack=0x600ff000
  u.mem_map(0x70000000,0x10000); self.stop=0x70000000
  self.funcs={};self.exports={s.name.decode():self.base+s.address for s in self.pe.DIRECTORY_ENTRY_EXPORT.symbols}
  for imp in self.pe.DIRECTORY_ENTRY_IMPORT:
   for ent in imp.imports:
    a=0x70001000+len(self.funcs)*16;self.funcs[a]=ent.name.decode();u.mem_write(ent.address,struct.pack('<I',a));u.mem_write(a,b'\xc3')
  self.files={};self.tls={};self.last=[];self.trace=False
  u.hook_add(UC_HOOK_CODE,self.hook,begin=0x70000000,end=0x7000ffff)
  u.hook_add(UC_HOOK_MEM_INVALID,self.invalid)
 def read(self,a,n=4):return bytes(self.uc.mem_read(a,n))
 def dword(self,a):return struct.unpack('<I',self.read(a))[0]
 def write32(self,a,v):self.uc.mem_write(a,struct.pack('<I',v&0xffffffff))
 def alloc(self,n):
  a=self.next;self.next+=(max(n,1)+4095)&~4095
  if self.next>=0x14000000:raise ValueError('heap exhausted')
  return a
 def cstr(self,a):
  if not a:return ''
  b=bytearray()
  while (x:=self.read(a+len(b),1))!=b'\0':b+=x
  return b.decode('latin1')
 def ret(self,val,n):
  sp=self.uc.reg_read(UC_X86_REG_ESP);ra=self.dword(sp)
  self.uc.reg_write(UC_X86_REG_EAX,val&0xffffffff);self.uc.reg_write(UC_X86_REG_ESP,sp+4+n*4);self.uc.reg_write(UC_X86_REG_EIP,ra)
 def hook(self,u,a,size,data):
  self.last=(self.last+[a])[-15:]
  if a==self.stop:u.emu_stop();return
  if a not in self.funcs:return
  name=self.funcs[a];sp=u.reg_read(UC_X86_REG_ESP);args=[self.dword(sp+4+i*4) for i in range(10)]
  if self.trace:print(name,[hex(x) for x in args[:4]],flush=True)
  if name in ('InitializeCriticalSection','EnterCriticalSection','LeaveCriticalSection','DeleteCriticalSection'):self.ret(1,1)
  elif name=='VirtualAlloc':self.ret(self.alloc(args[1]),4)
  elif name=='VirtualFree':self.ret(1,3)
  elif name=='LocalAlloc':self.ret(self.alloc(args[1]),2)
  elif name in ('LocalFree','CloseHandle','TlsFree','FreeLibrary'):self.ret(0 if name=='LocalFree' else 1,1)
  elif name=='TlsAlloc':self.ret(1,0)
  elif name=='TlsGetValue':self.ret(self.tls.get(args[0],0),1)
  elif name=='TlsSetValue':self.tls[args[0]]=args[1];self.ret(1,2)
  elif name=='GetVersionExA':u.mem_write(args[0]+4,struct.pack('<IIII',6,1,7601,2));self.ret(1,1)
  elif name=='lstrcpynA':u.mem_write(args[0],self.cstr(args[1]).encode('latin1')[:max(0,args[2]-1)]+b'\0');self.ret(args[0],3)
  elif name=='lstrlenA':self.ret(len(self.cstr(args[0])),1)
  elif name=='LoadStringA':u.mem_write(args[2],b'Error\0');self.ret(5,4)
  elif name=='LoadLibraryExA':self.ret(0,3)
  elif name=='GetProcAddress':self.ret(0,2)
  elif name=='GetSystemMetrics':self.ret(0,1)
  elif name=='RegOpenKeyExA':self.ret(2,5)
  elif name=='GetKeyboardType':self.ret(4 if args[0]==0 else 0,1)
  elif name=='GetCurrentThreadId':self.ret(1,0)
  elif name=='GetStartupInfoA':self.ret(0,1)
  elif name in ('GetThreadLocale','GetACP'):self.ret(0x409 if name=='GetThreadLocale' else 1252,0)
  elif name=='GetModuleHandleA':self.ret(self.base,1)
  elif name=='GetCommandLineA':
   p=self.alloc(100);u.mem_write(p,b'"C:\\tak.exe"\0');self.ret(p,0)
  elif name=='GetModuleFileNameA':
   s=b'C:\\tak_deco_lib.dll\0';u.mem_write(args[1],s);self.ret(len(s)-1,3)
  elif name=='GetStdHandle':self.ret(0,1)
  elif name=='GetCPInfo':u.mem_write(args[1],struct.pack('<I',1)+b'?\0'+b'\0'*14);self.ret(1,2)
  elif name=='GetLocaleInfoA':
   s=b'1252\0';u.mem_write(args[2],s[:args[3]]);self.ret(len(s),4)
  elif name=='EnumCalendarInfoA':self.ret(1,4)
  elif name=='GetLastError':self.ret(0,0)
  elif name=='CreateFileA':
   fn=self.cstr(args[0]);self.files[0x100]=open(self.input_path,'rb');self.ret(0x100,7)
  elif name=='GetFileSize':
   f=self.files[args[0]];p=f.tell();f.seek(0,2);n=f.tell();f.seek(p)
   if args[1]:self.write32(args[1],n>>32)
   self.ret(n,2)
  elif name=='SetFilePointer':
   f=self.files[args[0]];off=args[1] if args[1]<0x80000000 else args[1]-0x100000000
   if args[2]:off+=(self.dword(args[2]) if self.dword(args[2])<0x80000000 else self.dword(args[2])-0x100000000)<<32
   n=f.seek(off,args[3]);self.ret(n,4)
  elif name=='ReadFile':
   f=self.files[args[0]];b=f.read(args[2]);u.mem_write(args[1],b)
   if args[3]:self.write32(args[3],len(b))
   self.ret(1,5)
  else:raise RuntimeError(f'Unhandled import {name}: {[hex(x) for x in args[:6]]}')
 def invalid(self,u,access,a,size,value,data):
  raise RuntimeError(f'DLL accessed unmapped memory at {a:#x}')
 def call(self,fn,*args):
  a=self.exports[fn] if isinstance(fn,str) else fn
  u=self.uc;u.reg_write(UC_X86_REG_ESP,self.stack-4*(len(args)+1));u.mem_write(self.stack-4*(len(args)+1),struct.pack('<'+'I'*(len(args)+1),self.stop,*args))
  u.reg_write(UC_X86_REG_EBP,0);u.reg_write(UC_X86_REG_EAX,0);u.reg_write(UC_X86_REG_EDX,0);u.reg_write(UC_X86_REG_ECX,0)
  u.emu_start(a,self.stop,count=100000000)
  if u.reg_read(UC_X86_REG_EIP)!=self.stop:raise RuntimeError('Instruction budget exceeded')
  return u.reg_read(UC_X86_REG_EAX)

def open_reference(dll, source):
    oracle = Oracle(str(dll))
    oracle.call(oracle.base+oracle.pe.OPTIONAL_HEADER.AddressOfEntryPoint, oracle.base, 1, 0)
    oracle.input_path = str(source)
    filename = oracle.alloc(64)
    oracle.uc.mem_write(filename, b"reference.tak\0")
    options = oracle.alloc(64)
    handle = oracle.call("tak_SSD_Create_FromFile", filename, options, 0, 0)
    if not oracle.call("tak_SSD_Valid", handle):
        raise RuntimeError(f"Reference DLL could not open the file; state {oracle.call('tak_SSD_State', handle):#x}")
    return oracle, handle

def compare(dll, source, native, packets=None):
    import subprocess, tempfile, hashlib
    with tempfile.TemporaryDirectory(prefix="loopdeck-tak1-") as temp:
        output = Path(temp)/"native.pcm"
        command = [str(native), str(source), str(output)]
        if packets is not None:
            command.append(str(packets))
        subprocess.run(command, check=True)
        actual = output.read_bytes()
    oracle, handle = open_reference(dll, source)
    info = oracle.alloc(128)
    if oracle.call("tak_SSD_GetStreamInfo", handle, info):
        raise RuntimeError("Cannot read reference stream information")
    block = oracle.dword(info+40)
    if not block or len(actual)%block:
        raise RuntimeError("Native output is not aligned to reference PCM frames")
    destination, got = oracle.alloc(16384*block), oracle.alloc(4)
    compared = 0
    try:
        while compared < len(actual):
            frames = min(16384, (len(actual)-compared)//block)
            status = oracle.call("tak_SSD_ReadAudio", handle, destination, frames, got)
            if status or oracle.dword(got) != frames:
                raise RuntimeError(f"Reference failed after {compared} bytes; status {status:#x}")
            reference = oracle.read(destination, frames*block)
            chunk = actual[compared:compared+len(reference)]
            if chunk != reference:
                offset = next(i for i,(a,b) in enumerate(zip(chunk,reference)) if a != b)
                raise RuntimeError(f"PCM mismatch at byte {compared+offset}")
            compared += len(reference)
    finally:
        oracle.call("tak_SSD_Destroy", handle)
        for file in oracle.files.values():
            file.close()
    print(f"Exact PCM match: {compared} bytes; sha256 {hashlib.sha256(actual).hexdigest()}")

if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dll", type=Path, required=True)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--native", type=Path, required=True)
    parser.add_argument("--packets", type=int, help="Limit both sides to complete packets in a truncated sample")
    args = parser.parse_args()
    compare(args.dll.resolve(), args.input.resolve(), args.native.resolve(), args.packets)

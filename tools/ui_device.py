#!/usr/bin/env python3
"""Small locator-based ADB helper for manual/automated project verification."""
import re
import subprocess
import time
import shlex
import xml.etree.ElementTree as ET

class Device:
    def __init__(self, serial): self.serial = serial
    def adb(self, *args):
        return subprocess.check_output(['adb', '-s', self.serial, *args], stderr=subprocess.STDOUT).decode(errors='replace')
    def shell(self, *args): return self.adb('shell', shlex.join(args))
    def driver(self, operation, label=None, value=None):
        args=['am','instrument','-w','-e','operation',operation]
        if label is not None: args+=['-e','label',label]
        if value is not None: args+=['-e','value',str(value)]
        args+=['dev.routemock.probe/.UiDriver']
        for attempt in range(3):
            output=self.shell(*args)
            if 'result=success' in output: return output
            if 'Accessibility root was not ready' in output and attempt < 2:
                time.sleep(1)
                continue
            raise AssertionError(output)
    def hierarchy(self):
        output=self.driver('dump')
        match=re.search(r'hierarchy=(<node.*?</node>)(?=\nINSTRUMENTATION_)',output,re.DOTALL)
        if not match: raise AssertionError('No hierarchy: '+output)
        return ET.fromstring(match.group(1))
    def node(self, label):
        root = self.hierarchy()
        for node in root.iter('node'):
            if label in (node.get('content-desc'), node.get('text'), node.get('resource-id')):
                return node
        raise AssertionError(f'Locator not found: {label}; texts=' + repr([n.get('text') for n in root.iter('node') if n.get('text')]))
    def click(self, label):
        self.driver('click',label)
    def text(self,label,value): self.driver('text',label,value)
    def progress(self,label,value): self.driver('progress',label,value)
    def input(self, text): self.shell('input','text',text)
    def launch(self, component): self.shell('am','start','-n',component)
    def screenshot(self, path):
        with open(path,'wb') as out: subprocess.run(['adb','-s',self.serial,'exec-out','screencap','-p'],check=True,stdout=out)

if __name__ == '__main__':
    import sys
    d=Device(sys.argv[1])
    if len(sys.argv)>2: d.click(sys.argv[2])
    else:
        for n in d.hierarchy().iter('node'):
            if n.get('text') or n.get('content-desc'):
                print(n.get('text'), '|', n.get('content-desc'), '|', n.get('bounds'), '| enabled='+str(n.get('enabled')))

# BD-UN-JB

**Note : YOU NEED ALREADY JAILBROKEN PS5**

BD-UN-JB is RemoteJarLoader that can be used on already jailbroken PS5 upto 13.20 firmware.   
It supports on-screen logging and network logging.  

Send bdj_unpatch.elf to elfldr to unpatch BD-J.  
bdj_unpatch.elf will backup existing bdjstack.jar to bdjstack.jar.bak just in case.  

Then burn BD-UN-JB iso and run.  

**DO NOT REINSTALL FW, IT WILL WIPE THE PATCH AND LOSE BD-JB**

---

RemoteLogger server is listening on port 18194.  
Use log_client.py to get the log.  
I recommend first running the log_client.py then starting the BD-J app.  

RemoteJarLoader server is listening on port 9025.  
Use jar_client.py to send the jar file.  
You can use any other TCP payload sender too.  
Don't forget to set Main-Class in manifest.txt.  

---

Use john-tornblom's **[bdj-sdk](https://github.com/john-tornblom/bdj-sdk/)** and **[ps5-payload-sdk](https://github.com/ps5-payload-dev/sdk/)** for compiling.  

---

The poops_1.4.jar is now included in the BD-UN-JB.iso for convenient jailbreak chain.
I couldn't find any writeable location outside the sandbox to put the poops payload for the BD-J to chain with it dynamically, so if poops ever gets updated you will need to get back to sending the new payload manually (or burn a new disc if you are extra lazy like me). 

After digging around I found [PS5PM](https://x.com/Ifaicompa/status/1607667070669582336) mentioned which allows you to remount /system_ex etc as R/W which could work? not sure but my PS5 (11.60) couldn't load the PS5PM elf so I gave up.

After loading the BD-J you can press [X] to immediately load the internal poops exploit.
Otherwise the remote jar loader is running so you can manually load other payload just like before.

---

### Credits

* **[Gezine](https://github.com/Gezine)** — Original BD-UN-JB sources.  
* **[TheFlow](https://github.com/theofficialflow)** — BD-JB documentation & native code execution sources.  
* **[hammer-83](https://github.com/hammer-83)** — PS5 Remote JAR Loader reference.  
* **[john-tornblom](https://github.com/john-tornblom)** — [BDJ-SDK](https://github.com/john-tornblom/bdj-sdk) and [ps5-payload-sdk](https://github.com/ps5-payload-dev/sdk/) used for compilation.  
* **[kuba--](https://github.com/kuba--)** — [zip](https://github.com/kuba--/zip) used for bdj_unpatch elf payload.  

---



































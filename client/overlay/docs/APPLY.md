# Apply overlay to a full QMSync checkout (so you get a buildable mod)

This repo intentionally does NOT vendor the whole mod (images/libs jars). To build:

```powershell
# 1. full mod base (1.21.11)
git clone -b 1.21.11 https://github.com/KarolexDev/QMSync.git qmsync-mod
cd qmsync-mod
copy .env.example .env   # GITHUB_ACTOR + GITHUB_TOKEN (read:packages)

# 2. overlay
robocopy C:\Users\ADMIN\Documents\randomprograms\chesttrackermultiplayersync\client\overlay\src\client\java\red\jackf\chesttracker\impl\cmsync src\client\java\red\jackf\chesttracker\impl\cmsync /E

# 3. hooks in src\client\java\red\jackf\chesttracker\impl\ChestTracker.java onInitializeClient():
#    CMSyncManager.INSTANCE.setup();
#    CMSyncCommand.register();
#    (imports: red.jackf.chesttracker.impl.cmsync.CMSyncManager, CMSyncCommand)

# 4. build
.\gradlew.bat check build
.\gradlew.bat runClient
```

Repeat on `26.1.2`/`26.2` branches with the same overlay copy (see `PORTING-26x.md`).

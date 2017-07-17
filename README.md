**NOTE: This is a fork of the [gradle](https://github.com/gradle/gradle) project, where we added a 
[pull request](https://github.com/gradle/gradle/pull/1725/commits/2ecc6c1c157656fb5194e4cadbf2f7a400b7d4a6).**

For more information look at the original [gradle](https://github.com/gradle/gradle) repository. 
You can install this forked version of gradle as explained below.

### Installing from source

To create an install from the source tree you can run either of the following:

    ./gradlew install -Pgradle_installPath=/usr/local/gradle-source-build

This will create a minimal installation; just what's needed to run Gradle (i.e. no docs). Note that the `-Pgradle_installPath` denotes where to install to.

You can then build a Gradle based project with this installation:

    /usr/local/gradle-source-build/bin/gradle «some task»

To create a full installation (includes docs):

    ./gradlew installAll -Pgradle_installPath=/usr/local/gradle-source-build

### Contributing Documentation

Please see the readme in the [docs subproject](https://github.com/gradle/gradle/tree/master/subprojects/docs).



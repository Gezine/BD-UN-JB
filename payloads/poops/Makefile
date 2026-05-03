JAR_NAME := poops.jar

MAKEFILE_DIR := $(dir $(realpath $(lastword $(MAKEFILE_LIST))))
BDJSDK_HOME  ?= $(MAKEFILE_DIR)/../../../../
JAVA8_HOME   ?= $(BDJSDK_HOME)/host/jdk8
JAVA11_HOME  ?= $(BDJSDK_HOME)/host/jdk11
JAVAC        := $(JAVA8_HOME)/bin/javac
JAR          := $(JAVA8_HOME)/bin/jar

export JAVA8_HOME
export JAVA11_HOME

CLASSPATH     := $(BDJSDK_HOME)/target/lib/enhanced-stubs.zip:$(BDJSDK_HOME)/target/lib/bdjstack.jar:$(BDJSDK_HOME)/target/lib/rt.jar
SOURCES       := $(wildcard src/org/bdj/api/*.java) $(wildcard src/org/bdj/*.java) $(wildcard src/org/bdj/external/*.java) 
JFLAGS        := -Xlint:-options -source 1.4 -target 1.4

all: $(JAR_NAME)

# Create JAR with manifest
$(JAR_NAME): $(SOURCES) manifest.txt
	$(JAVAC) $(JFLAGS) -cp $(CLASSPATH) $(SOURCES)
	$(JAR) cfm $@ manifest.txt -C src/ .

clean:
	rm -f src/org/bdj/api/*.class src/org/bdj/*.class src/org/bdj/external/*.class $(JAR_NAME)

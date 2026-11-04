#!/bin/sh
# Arranca uma classe Java com o ORB do JacORB.
# Uso: run.sh [classe] [argumentos]   (sem argumentos usa a variavel CLASSE)
if [ $# -gt 0 ]; then CLASSE=$1; shift; fi
exec java \
  -Xbootclasspath/p:/app/lib/jacorb-omgapi-3.9.jar \
  -Dorg.omg.CORBA.ORBClass=org.jacorb.orb.ORB \
  -Dorg.omg.CORBA.ORBSingletonClass=org.jacorb.orb.ORBSingleton \
  $JAVA_OPTS \
  -cp '/app/app.jar:/app/lib/*' \
  $CLASSE "$@"

rm -r class
mkdir class
javac src/*.java -d class
cd class
jar cf ../out/artifacts/Test_jar/Test.jar *.class
cd ..

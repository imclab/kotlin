package testing;

import test.rename.KotlinClass;

class JavaClient {
    static class Some extends KotlinClass {
        @Override
        public String getFoo() {
            return "Overriden";
        }

        @Override
        public void setFoo(String value) {
        }
    }
}


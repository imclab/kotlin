package testing;

import test.rename.KotlinClass;

class JavaClient {
    static class Some extends KotlinClass {
        @Override
        public String getBar() {
            return "Overriden";
        }

        @Override
        public void setBar(String value) {
        }
    }
}
/*
 * Copyright 2017 Alicia Boya García
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ntrrgc.tsGenerator.tests;

import java.util.function.Consumer;

interface GrandFatherInterface {
    default void someMethodYouAreNotExpecting() {

    }
}

interface BaseInterfaceWithDefaultMethod extends GrandFatherInterface {
    void interfaceMethod();

    default void interfaceMethodWithDefaultImplementation() {
        System.out.println("This method has a default implementation but that doesnt matter");
    }
}

public class JavaClass implements BaseInterfaceWithDefaultMethod {
    private String name;
    private int[] results;
    private boolean finished;
    private char[][] multidimensional;
    private int notABean;

    public int publicInt;

    public int getPublicInt() {
        return publicInt;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int[] getResults() {
        return results;
    }

    public void setResults(int[] results) {
        this.results = results;
    }

    public boolean isFinished() {
        return finished;
    }

    public char[][] getMultidimensional() {
        return multidimensional;
    }

    public void setMultidimensional(char[][] multidimensional) {
        this.multidimensional = multidimensional;
    }

    @Override
    public void interfaceMethod() {

    }

    public Consumer<Consumer<Consumer<Integer>>> isThatNullable;

    public Class<Class<Class<Class<Integer>>>> whatAboutThis;


    public Consumer<Integer> integerConsumer;

    public <E> void genericMethod(E _unused) {

    }

    public <E, R> R anotherGenericMethod(E _unused) {
        return null;
    }

    public <E extends Comparable<E>> E yetAnotherGenericMethod(E _unused, E other) {
        return null;
    }
}

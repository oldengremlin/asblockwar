/*
 * Copyright 2026 olden.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.ukrcom.asblockwar.actions;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import lombok.extern.slf4j.Slf4j;

/**
 * Пул віртуальних потоків, який не втрачає винятки.
 * <p>
 * {@code ExecutorService.submit()} загортає задачу у {@code FutureTask}, тож будь-який
 * виняток осідає у {@code Future}. Оскільки в проєкті результат {@code submit()}
 * майже всюди відкидається, помилки під час обробки ASN зникали безслідно —
 * ні в лог, ні в обробник. Тут задачі запускаються через {@code execute()},
 * а фабрика потоків має {@code uncaughtExceptionHandler}, який їх логує.
 */
@Slf4j
public final class VirtualExecutor {

    private VirtualExecutor() {
    }

    /**
     * Створює executor на віртуальних потоках із логуванням необроблених винятків.
     *
     * @param name префікс імені потоків — потрапляє в лог при помилці
     * @return новий executor; закривати через try-with-resources
     */
    public static ExecutorService create(String name) {
        ThreadFactory factory = Thread.ofVirtual()
                .name(name + "-", 0)
                .uncaughtExceptionHandler((thread, ex)
                        -> log.error("Необроблена помилка у задачі {}", thread.getName(), ex))
                .factory();
        return Executors.newThreadPerTaskExecutor(factory);
    }
}

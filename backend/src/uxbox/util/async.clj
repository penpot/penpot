;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.async
  (:require [clojure.core.async :as a]))

(defmacro go-try
  [& body]
  `(a/go
     (try
       ~@body
       (catch Throwable e# e#))))

(defmacro <?
  [ch]
  `(let [r# (a/<! ~ch)]
     (if (instance? Throwable r#)
       (throw r#)
       r#)))

(defmacro thread-try
  [& body]
  `(a/thread
     (try
       ~@body
       (catch Throwable e#
         e#))))


;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2021 UXBOX Labs SL

(ns app.util.graal
  "Graal Polyglot integration layer."
  (:import
   java.util.function.Consumer
   org.graalvm.polyglot.Context
   org.graalvm.polyglot.Source
   org.graalvm.polyglot.Value))

(defn ^Source source
  [lang content]
  (if (string? content)
    (Source/create ^String lang ^String content)
    (.. (Source/newBuilder lang content)
        (build))))

(defn ^Context context
  [lang]
  (.. (Context/newBuilder (into-array String [lang]))
      (allowAllAccess true)
      (allowIO true)
      (build)))

(defn ^Value eval!
  [ctx source]
  (.eval ^Context ctx ^Source source))

(defn ^Value get-bindings
  [ctx]
  (.getBindings ^Context ctx))

(defn ^Value get-member
  [vobj name]
  (.getMember ^Value vobj ^String name))

(defn ^Value invoke
  [vobj & params]
  (.execute ^Value vobj (into-array Object params)))

(defn ^Value invoke-member
  [vobj name & params]
  (let [params (into-array Object params)]
    (.invokeMember ^Value vobj ^String name params)))

(defn ^Value set-member!
  [vobj name obj]
  (.putMember ^Value vobj ^String name ^Object obj)
  vobj)

(defn close!
  [ctx]
  (when ctx
    (.close ^Context ctx)))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.system-test
  "The async system lifecycle: dependency-ordered init and halt, with
  refs resolved to the running instances."
  (:require
   [cljs.test :as t :include-macros true]
   [exporter.utils.system :as system]))

;; Each init method records its key on the tracker carried by its own
;; config, and returns a running instance that carries the tracker back,
;; so the matching halt method can record on the same atom.
(defn- track-init
  [tracker k]
  (swap! tracker conj k)
  {:tracker tracker :component k})

(defn- track-halt
  [tracker k]
  (swap! tracker conj [:halted k])
  nil)

(defmethod system/init-key :order-test/a [_ {:keys [tracker]}] (track-init tracker :order-test/a))
(defmethod system/init-key :order-test/b [_ {:keys [tracker]}] (track-init tracker :order-test/b))
(defmethod system/init-key :order-test/c [_ {:keys [tracker]}] (track-init tracker :order-test/c))
(defmethod system/halt-key :order-test/a [_ {:keys [tracker]}] (track-halt tracker :order-test/a))
(defmethod system/halt-key :order-test/b [_ {:keys [tracker]}] (track-halt tracker :order-test/b))
(defmethod system/halt-key :order-test/c [_ {:keys [tracker]}] (track-halt tracker :order-test/c))

(defn- order-config
  [tracker]
  {:order-test/a {:tracker tracker}
   :order-test/b {:tracker tracker :dep (system/ref :order-test/a)}
   :order-test/c {:tracker tracker :dep (system/ref :order-test/b)}})

(defmethod system/init-key :refs-test/db [_ {:keys [tracker]}]
  (swap! tracker conj :refs-test/db)
  {:tracker tracker :component :refs-test/db :conn :fake-conn})

(defmethod system/init-key :refs-test/api [_ {:keys [tracker dep]}]
  (swap! tracker conj [:refs-test/api-sees dep])
  {:tracker tracker :component :refs-test/api})

(defmethod system/init-key :async-test/db [_ _]
  (js/Promise. (fn [resolve _] (js/setTimeout (fn [] (resolve :async-conn)) 5))))

(defmethod system/init-key :async-test/api [_ {:keys [dep]}]
  {:component :async-test/api :dep dep})

(defmethod system/init-key :fail-test/a [_ {:keys [tracker]}] (track-init tracker :fail-test/a))
(defmethod system/init-key :fail-test/b [_ {:keys [tracker fail?]}]
  (swap! tracker conj :fail-test/b)
  (if fail?
    (js/Promise.reject (ex-info "cannot connect" {}))
    {:tracker tracker :component :fail-test/b}))
(defmethod system/halt-key :fail-test/a [_ {:keys [tracker]}] (track-halt tracker :fail-test/a))

(defmethod system/init-key :haltfail-test/a [_ {:keys [tracker]}] (track-init tracker :haltfail-test/a))
(defmethod system/init-key :haltfail-test/b [_ {:keys [tracker boom?]}]
  (assoc (track-init tracker :haltfail-test/b) :boom? boom?))
(defmethod system/halt-key :haltfail-test/a [_ {:keys [tracker]}] (track-halt tracker :haltfail-test/a))
(defmethod system/halt-key :haltfail-test/b [_ {:keys [tracker boom?]}]
  (swap! tracker conj [:haltfail-test/b-ran boom?])
  (when boom?
    (throw (ex-info "cannot stop" {})))
  nil)

(derive :derive-test/child :derive-test/parent)
(derive :derive-test/other :derive-test/parent)

(defmethod system/init-key :derive-test/child [_ _] :child-running)
(defmethod system/init-key :derive-test/other [_ _] :other-running)
(defmethod system/init-key :derive-test/consumer [_ {:keys [dep]}] dep)
(defmethod system/init-key :derive-test/consumer2 [_ {:keys [dep]}] dep)

(defmethod system/init-key :circ-test/a [_ _] :circ-a-running)
(defmethod system/init-key :circ-test/b [_ _] :circ-b-running)

(defmethod system/init-key :nohalt-test/a [_ _] :nohalt-a-running)

(t/deftest ^:async inits-keys-in-dependency-order
  (try
    (let [tracker (atom [])
          sys     (await (system/init (order-config tracker)))]
      (t/is (= [:order-test/a :order-test/b :order-test/c] @tracker))
      (t/is (= #{:order-test/a :order-test/b :order-test/c} (set (keys sys)))))
    (catch :default cause
      (t/is false (str "unexpected init failure: " (ex-message cause))))))

(t/deftest ^:async resolves-refs-to-running-instances
  (try
    (let [tracker (atom [])
          sys     (await (system/init {:refs-test/db  {:tracker tracker}
                                       :refs-test/api {:tracker tracker
                                                       :dep     (system/ref :refs-test/db)}}))]
      (t/is (= [:refs-test/db
                [:refs-test/api-sees {:tracker   tracker
                                      :component :refs-test/db
                                      :conn      :fake-conn}]]
               @tracker))
      (t/is (= :refs-test/api (:component (:refs-test/api sys)))))
    (catch :default cause
      (t/is false (str "unexpected init failure: " (ex-message cause))))))

(t/deftest ^:async awaits-promised-instances
  (try
    (let [sys (await (system/init {:async-test/db  {}
                                   :async-test/api {:dep (system/ref :async-test/db)}}))]
      (t/is (= :async-conn (:async-test/db sys)))
      (t/is (= :async-conn (:dep (:async-test/api sys)))))
    (catch :default cause
      (t/is false (str "unexpected init failure: " (ex-message cause))))))

(t/deftest ^:async missing-ref-fails-init
  (try
    (await (system/init {:missing-test/a {:dep (system/ref :missing-test/ghost)}}))
    (t/is false "init should have rejected on a missing ref")
    (catch :default cause
      (t/is (= ::system/missing-refs (-> cause ex-data :reason)))
      (t/is (= [:missing-test/ghost] (-> cause ex-data :missing-refs))))))

(t/deftest ^:async failed-init-halts-what-already-started
  (let [tracker (atom [])]
    (try
      (await (system/init {:fail-test/a {:tracker tracker}
                           :fail-test/b {:tracker tracker
                                         :fail?   true
                                         :dep     (system/ref :fail-test/a)}}))
      (t/is false "init should have rejected when a component fails")
      (catch :default cause
        (t/is (= ::system/init-failed (-> cause ex-data :reason)))
        (t/is (= :fail-test/b (-> cause ex-data :key)))
        (t/is (= [:fail-test/a :fail-test/b [:halted :fail-test/a]] @tracker))))))

(t/deftest ^:async halts-in-reverse-dependency-order
  (try
    (let [tracker (atom [])
          sys     (await (system/init (order-config tracker)))
          result  (await (system/halt sys))]
      (t/is (nil? result))
      (t/is (= [[:halted :order-test/c]
                [:halted :order-test/b]
                [:halted :order-test/a]]
               (drop 3 @tracker))))
    (catch :default cause
      (t/is false (str "unexpected halt failure: " (ex-message cause))))))

(t/deftest ^:async halt-continues-past-a-failing-component
  (try
    (let [tracker (atom [])
          sys     (await (system/init {:haltfail-test/a {:tracker tracker}
                                       :haltfail-test/b {:tracker tracker
                                                         :boom?   true
                                                         :dep     (system/ref :haltfail-test/a)}}))]
      (try
        (await (system/halt sys))
        (t/is false "halt should have rejected when a component fails to stop")
        (catch :default cause
          (t/is (= ::system/halt-failed (-> cause ex-data :reason)))
          (t/is (= [:haltfail-test/b] (mapv :key (-> cause ex-data :failures))))))
      (t/is (= [:haltfail-test/a
                :haltfail-test/b
                [:haltfail-test/b-ran true]
                [:halted :haltfail-test/a]]
               @tracker)))
    (catch :default cause
      (t/is false (str "unexpected init failure: " (ex-message cause))))))

(t/deftest ^:async init-subset-pulls-its-dependencies
  (try
    (let [tracker (atom [])
          sys     (await (system/init (order-config tracker) [:order-test/c]))]
      (t/is (= [:order-test/a :order-test/b :order-test/c] @tracker))
      (t/is (= #{:order-test/a :order-test/b :order-test/c} (set (keys sys)))))
    (catch :default cause
      (t/is false (str "unexpected init failure: " (ex-message cause))))))

(t/deftest ^:async ref-to-a-parent-resolves-the-derived-child
  (try
    (let [sys (await (system/init {:derive-test/child    {}
                                   :derive-test/consumer {:dep (system/ref :derive-test/parent)}}))]
      (t/is (= :child-running (:derive-test/consumer sys))))
    (catch :default cause
      (t/is false (str "unexpected init failure: " (ex-message cause))))))

(t/deftest ^:async ref-to-a-parent-with-two-children-is-ambiguous
  (try
    (await (system/init {:derive-test/child     {}
                         :derive-test/other     {}
                         :derive-test/consumer2 {:dep (system/ref :derive-test/parent)}}))
    (t/is false "init should have rejected on an ambiguous ref")
    (catch :default cause
      (t/is (= ::system/ambiguous-key (-> cause ex-data :reason))))))

(t/deftest ^:async circular-dependencies-fail-init
  (try
    (await (system/init {:circ-test/a {:dep (system/ref :circ-test/b)}
                         :circ-test/b {:dep (system/ref :circ-test/a)}}))
    (t/is false "init should have rejected on a circular dependency")
    (catch :default cause
      (t/is (= :weavejester.dependency/circular-dependency
               (-> cause ex-data :reason))))))

(t/deftest ^:async key-without-halt-method-halts-cleanly
  (try
    (let [sys    (await (system/init {:nohalt-test/a {}}))
          result (await (system/halt sys))]
      (t/is (= :nohalt-a-running (:nohalt-test/a sys)))
      (t/is (nil? result)))
    (catch :default cause
      (t/is false (str "unexpected failure: " (ex-message cause))))))

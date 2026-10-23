;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.tasks-event-consumer-test
  "Registro de reacciones del consumidor de eventos del audit log: qué
  reacción reclama cada evento y qué hace con el lote que le toca."
  (:require
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.tasks.event-consumer :as event-consumer]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- capture-reaction
  "Una reacción de mentira que guarda el lote que recibe."
  [seen events]
  {:id     :test
   :flag   :test-flag
   :events events
   :apply  (fn [_cfg batch] (swap! seen conj batch))})

(t/deftest dispatch-applies-a-reaction-to-the-events-it-declares
  (let [seen     (atom [])
        reaction (capture-reaction seen #{"delete-file"})
        event-a  {:id (uuid/next) :name "delete-file" :props {}}
        event-b  {:id (uuid/next) :name "create-file" :props {}}]
    (with-redefs [cf/flags #{:test-flag}
                  event-consumer/reactions [reaction]]
      (let [claimed (event-consumer/dispatch-reactions
                     th/*system*
                     [event-a event-b])]
        (t/is (= 1 claimed))
        (t/is (= 1 (count @seen)))
        (t/is (= ["delete-file"] (mapv :name (first @seen))))))))

(t/deftest dispatch-ignores-the-events-no-reaction-claims
  ;; La cola del consumidor es no selectiva: los eventos que nadie
  ;; reclama se descartan.
  (let [seen     (atom [])
        reaction (capture-reaction seen #{"delete-file"})]
    (with-redefs [cf/flags #{:test-flag}
                  event-consumer/reactions [reaction]]
      (let [claimed (event-consumer/dispatch-reactions
                     th/*system*
                     [{:id (uuid/next) :name "create-file" :props {}}])]
        (t/is (= 0 claimed))
        (t/is (empty? @seen))))))

(t/deftest dispatch-skips-a-reaction-whose-flag-is-off
  ;; La flag de la reacción es la que la enciende: sin ella, el evento
  ;; no se reclama y la reacción no corre.
  (let [seen     (atom [])
        reaction (capture-reaction seen #{"delete-file"})]
    (with-redefs [cf/flags #{}
                  event-consumer/reactions [reaction]]
      (let [claimed (event-consumer/dispatch-reactions
                     th/*system*
                     [{:id (uuid/next) :name "delete-file" :props {}}])]
        (t/is (= 0 claimed))
        (t/is (empty? @seen))))))

(t/deftest dispatch-gives-each-reaction-only-its-own-events
  ;; Dos reacciones que declaran el mismo nombre: cada una recibe su
  ;; lote y el evento se cuenta una sola vez.
  (let [seen-a   (atom [])
        seen-b   (atom [])
        reaction-a (capture-reaction seen-a #{"delete-file"})
        reaction-b (capture-reaction seen-b #{"delete-file" "create-file"})
        events  [{:id (uuid/next) :name "delete-file" :props {}}
                 {:id (uuid/next) :name "create-file" :props {}}]]
    (with-redefs [cf/flags #{:test-flag}
                  event-consumer/reactions [reaction-a reaction-b]]
      (let [claimed (event-consumer/dispatch-reactions th/*system* events)]
        (t/is (= 2 claimed))
        (t/is (= ["delete-file"] (mapv :name (first @seen-a))))
        (t/is (= ["delete-file" "create-file"] (mapv :name (first @seen-b))))))))

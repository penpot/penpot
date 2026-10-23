;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.tasks.event-consumer
  "Registro de reacciones del consumidor de eventos del audit log.

  El consumidor de eventos (el id 3 de `audit_log_pending`) es el único
  que no archiva nada: lee el flujo de eventos y deja que cada reacción
  decida qué le interesa. Una reacción es un mapa:

    {:id     :team-storage-usage   ;; quién es
     :flag   :storage-usage        ;; la flag que la enciende
     :events #{...}                ;; los nombres de evento que le importan
     :apply  (fn [cfg events] ...)} ;; qué hace con ellos

  Cada dominio aporta la suya: quien tiene la tabla y el SQL de su marca
  es quien sabe qué eventos le afectan y de dónde sale su clave.

  La reacción recibe el lote de eventos que declara, una sola vez: una
  reacción que agrupa trabajo (marcar un equipo sucio) no debe escribir
  una vez por evento. El despacho no captura excepciones: la reacción
  decide qué errores son de datos (se salta el evento) y cuáles abortan
  el lote (el evento se reintenta en el siguiente tick)."
  (:require
   [app.config :as cf]
   [app.tasks.team-storage :as team-storage]))

(def reactions
  "Las reacciones registradas, en el orden en que se aplican."
  [team-storage/team-storage-reaction])

(defn enabled-reactions
  "Las reacciones cuya flag está encendida."
  []
  (filterv #(contains? cf/flags (:flag %)) reactions))

(defn- claimed-by
  "Las reacciones activas que declaran el nombre de este evento."
  [reactions event]
  (filterv #(contains? (:events %) (:name event)) reactions))

(defn dispatch-reactions
  "Aplica las reacciones activas a los eventos. Cada reacción recibe solo
  los eventos que declara, y los recibe en un único lote.

  Devuelve el número de eventos que reclamó alguna reacción: los que no
  reclamó nadie se descartan, porque la cola del consumidor es no
  selectiva y ese evento no le interesa a nadie."
  [cfg events]
  (let [reactions (enabled-reactions)
        batches   (keep (fn [reaction]
                          (let [mine (filterv #(contains? (:events reaction) (:name %)) events)]
                            (when (seq mine)
                              [reaction mine])))
                        reactions)]
    (doseq [[reaction mine] batches]
      ((:apply reaction) cfg mine))
    (count (filter (fn [event]
                     (seq (claimed-by reactions event)))
                   events))))

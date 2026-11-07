;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.rpc-climit-test
  (:require
   [app.rpc :as-alias rpc]
   [app.rpc.climit :as-alias climit]
   [app.util.services :as-alias sv]
   [backend-tests.helpers :as th]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)

(defn- command-limits
  "The `::climit/id` declaration of the RPC command named `name`."
  [name]
  (let [[mdata _] (get (:app.rpc/methods th/*system*) (keyword name))]
    (::climit/id mdata)))

(defn- method-limit-declarations
  "The `::climit/id` declarations of every RPC command, as the running system
  wraps them."
  []
  (->> (:app.rpc/methods th/*system*)
       (vals)
       (map first)
       (keep (fn [mdata]
               (when-let [limits (::climit/id mdata)]
                 [mdata limits])))))

(defn- declared-ids
  "The limit ids a command declares, in the order it declares them."
  [limits]
  (cond
    (keyword? limits)
    [limits]

    (vector? (first limits))
    (mapv first limits)

    :else
    [(first limits)]))

(defn- limit-order-edges
  "The pairs of limit ids the commands declare, as edges: the first one of the
  pair is taken before the second one."
  [declarations]
  (reduce (fn [edges [mdata limits]]
            (let [ids  (declared-ids limits)
                  size (count ids)]
              (into edges
                    (for [i (range size)
                          j (range (inc i) size)]
                      [(nth ids i) (nth ids j)]))))
          #{}
          declarations))

(defn- limit-order-cycle
  "The limit ids whose relative order the commands disagree about: the ones
  left when the relation cannot be sorted.

  Two commands that take the same limiters in opposite order leave two paths
  of one profile holding them in opposite order, and the threads park for
  good. A ring of three does the same, so the relation is checked as a whole
  and not pair by pair."
  [edges]
  (let [nodes    (into #{} (mapcat identity) edges)
        outgoing (reduce (fn [acc [a b]] (update acc a (fnil conj #{}) b)) {} edges)
        indegree (reduce (fn [acc [_ b]] (update acc b (fnil inc 0))) {} edges)]
    (loop [pending nodes
           degrees indegree]
      (let [ready (filterv #(zero? (get degrees % 0)) pending)]
        (if (empty? ready)
          (seq pending)
          (recur (apply disj pending ready)
                 (reduce (fn [degrees node]
                           (reduce (fn [degrees next]
                                     (update degrees next (fnil dec 0)))
                                   degrees
                                   (get outgoing node)))
                         degrees
                         ready)))))))

(t/deftest the-media-object-from-url-command-declares-its-own-limits
  ;; The command used to enter climit from inside its handler, which forced
  ;; every path to take the root limiters before the command ones. It declares
  ;; its limits now, so there is a single place where they are read.
  (t/is (= [[:process-image/by-profile ::rpc/profile-id]
            [:process-image/global]]
           (command-limits "create-file-media-object-from-url"))))

(t/deftest the-update-profile-photo-command-declares-its-own-limits
  ;; The command used to enter climit from inside its handler, which forced
  ;; every path to take the root limiters before the command ones. It declares
  ;; its limits now, so there is a single place where they are read.
  (t/is (= [[:process-image/by-profile ::rpc/profile-id]
            [:process-image/global]]
           (command-limits "update-profile-photo"))))

(t/deftest the-update-team-photo-command-declares-its-own-limits
  ;; The command generates the thumbnail of a team through `upload-photo`,
  ;; which used to enter climit on its own. It declares the limits now, so the
  ;; image processing of a team is bounded like the one of a profile.
  (t/is (= [[:process-image/by-profile ::rpc/profile-id]
            [:process-image/global]]
           (command-limits "update-team-photo"))))

(t/deftest commands-declare-the-by-profile-limit-before-the-global-one
  ;; `climit/get-limits` reverses a declared vector of pairs, so the first one
  ;; declared is acquired first. A pair declared global first puts a command
  ;; back in the inverted order that parked two paths of one profile for good.
  (let [declarations (method-limit-declarations)]

    ;; the system wraps every command, so a short list means the scan went stale
    (t/is (<= 10 (count declarations)))

    (doseq [[mdata limits] declarations
            :when (and (vector? limits) (vector? (first limits)))]
      (let [ids   (mapv first limits)
            index (into {} (map-indexed (fn [idx id] [id idx]) ids))
            where (str (::sv/name mdata))]

        (t/is (= (count ids) (count (distinct ids)))
              (str where " declares the same limit twice"))

        (doseq [[id idx] index
                :when (= "by-profile" (name id))]
          (when-let [gidx (get index (keyword (namespace id) "global"))]
            (t/is (< idx gidx)
                  (str where " must declare " id " before "
                       (keyword (namespace id) "global")))))))))

(t/deftest every-command-takes-the-shared-limits-in-the-same-order
  ;; Two commands that share a limit and take it in opposite order can hold
  ;; the same limiters in opposite order and park for good. The order is free
  ;; now that no command enters climit from inside its handler, so this test
  ;; is what keeps the commands consistent with each other.
  (let [declarations (method-limit-declarations)
        edges       (limit-order-edges declarations)
        cycle       (limit-order-cycle edges)]

    ;; the system wraps every command, so a short list means the scan went stale
    (t/is (<= 10 (count declarations)))
    (t/is (seq edges))
    (t/is (nil? cycle)
          (str "the commands take these limits in different orders: "
               (pr-str cycle)))))

(t/deftest a-ring-of-three-limits-is-a-cycle
  ;; A ring of three limits does the same as a pair in opposite order: three
  ;; paths hold the three limiters and wait for each other. The relation is
  ;; checked as a whole for that reason, and not pair by pair.
  (t/is (= #{:a :b :c}
           (set (limit-order-cycle #{[:a :b] [:b :c] [:c :a]}))))
  (t/is (nil? (limit-order-cycle #{[:a :b] [:b :c]}))))

(t/deftest every-declared-limit-has-an-entry-in-the-shipped-config
  ;; `climit/wrap` logs a warning and runs the handler with no limiter at all
  ;; when the config has no entry for a declared id, so the command runs on
  ;; every thread that asks for it. The production file is maintained by hand
  ;; outside the repository and mirrors this one, so a command declaring a limit
  ;; the file does not list is the shape of an unbounded command in production.
  (let [path     (io/resource "climit.edn")
        config   (edn/read-string (slurp path))
        declared (into #{}
                       (mapcat (fn [[_ limits]] (declared-ids limits)))
                       (method-limit-declarations))]

    ;; the system wraps every command, so an empty set means the scan went stale
    (t/is (seq declared))

    (doseq [id declared]
      (t/is (some? (get config id))
            (str id " is declared by a command but has no entry in climit.edn")))))

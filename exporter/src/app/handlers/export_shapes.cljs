;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.handlers.export-shapes
  (:require
   [app.common.data :as d]
   [app.common.spec :as us]
   [app.handlers.resources :as rsc]
   [app.jobs :as jobs]
   [app.jobs.utils :as job.utils]
   [app.renderer :as rd]
   [app.util.mime :as mime]
   [app.util.shell :as sh]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(declare prepare-exports)

;; Regex to clean namefiles
(def sanitize-file-regex #"[\\/:*?\"<>|]")

(s/def ::file-id ::us/uuid)
(s/def ::filename ::us/string)
(s/def ::name ::us/string)
(s/def ::object-id ::us/uuid)
(s/def ::page-id ::us/uuid)
(s/def ::share-id ::us/uuid)
(s/def ::profile-id ::us/uuid)
(s/def ::scale ::us/number)
(s/def ::suffix ::us/string)
(s/def ::type ::us/keyword)
(s/def ::wait ::us/boolean)
(s/def ::is-wasm ::us/boolean)

(s/def ::export
  (s/keys :req-un [::page-id ::file-id ::object-id ::type ::suffix ::scale ::name]
          :opt-un [::share-id]))

(s/def ::exports
  (s/coll-of ::export :kind vector? :min-count 1))

(s/def ::params
  (s/keys :req-un [::exports ::profile-id]
          :opt-un [::wait ::name ::skip-children ::force-multiple ::is-wasm]))

(defn count-objects
  [exports]
  (reduce + 0 (map (comp count :objects) exports)))

(defn- render!
  [job export on-object]
  (jobs/check-cancelled! job)
  (rd/render (assoc export :job-id (:id job)) on-object))

(defn- scoped-renders
  "Renders every export, the headless ones sharing a single worker."
  [job exports on-object]
  (rd/with-scope exports
    (fn [render]
      (jobs/check-cancelled! job)
      (->> exports
           (map (fn [export] (render export on-object)))
           (p/all)))))

(defn- run-single
  [job auth-token resource {:keys [export is-wasm skip-children]}]
  (job.utils/track! (:id job) (:path resource))
  (->> (render! job
                (assoc export :skip-children skip-children :is-wasm (boolean is-wasm))
                (fn [{:keys [path] :as _object}]
                  (job.utils/track! (:id job) path)
                  (sh/move! path (:path resource))))
       (p/fmap (constantly resource))
       (p/mcat (partial rsc/upload-resource auth-token))
       (p/fmap (fn [resource] (dissoc resource :path)))))

(defn- run-multiple
  [job auth-token resource {:keys [exports is-wasm]}]
  (let [failure (volatile! nil)

        zip     (rsc/create-zip :resource resource
                                :on-error (fn [cause] (vreset! failure cause))
                                :on-progress (fn [{:keys [done]}]
                                               (jobs/progress! job done)))

        append  (fn [{:keys [filename path] :as _object}]
                  (job.utils/track! (:id job) path)
                  (rsc/add-to-zip zip path (str/replace filename sanitize-file-regex "_")))]

    (job.utils/track! (:id job) (:path resource))
    (->> (scoped-renders job
                         (map #(assoc % :is-wasm (boolean is-wasm) :job-id (:id job)) exports)
                         append)
         (p/mcat (fn [_]
                   (if-let [cause @failure]
                     (p/rejected cause)
                     (rsc/close-zip zip))))
         (p/fmap (constantly resource))
         (p/mcat (partial rsc/upload-resource auth-token))
         (p/fmap (fn [resource] (dissoc resource :path))))))

(defn headless-exports?
  "Whether any of `exports` renders headless, and so whether the job leases a
  render worker. Mirrors what `rd/with-scope` decides at run time."
  [exports is-wasm]
  (boolean (some #(rd/headless? {:is-wasm is-wasm :type (:type %)}) exports)))

(defn prepare
  [auth-token {:keys [exports force-multiple name skip-children is-wasm] :as _params}]
  (let [exports   (prepare-exports exports auth-token is-wasm)
        headless? (headless-exports? exports is-wasm)
        single?   (and (not force-multiple)
                       (= 1 (count exports))
                       (= 1 (count (-> exports first :objects))))]
    (if single?
      (let [export   (first exports)
            resource (rsc/create (:type export) (or name (:name export)))]
        {:resource resource
         :total 1
         :headless headless?
         :run (fn [job] (run-single job auth-token resource
                                    {:export export
                                     :is-wasm is-wasm
                                     :skip-children skip-children}))})

      (let [resource (rsc/create :zip (or name (-> exports first :name)))]
        {:resource resource
         :total (count-objects exports)
         :headless headless?
         :run (fn [job] (run-multiple job auth-token resource
                                      {:exports exports :is-wasm is-wasm}))}))))

(defn- assoc-file-name
  "A transducer that assocs a candidate filename and avoid duplicates"
  []
  (letfn [(find-candidate [params used]
            (loop [index 0]
              (let [candidate (str (:name params)
                                   (:suffix params "")
                                   (when (pos? index)
                                     (str/concat "-" (inc index)))
                                   (mime/get-extension (:type params)))]
                (if (contains? used candidate)
                  (recur (inc index))
                  candidate))))]
    (fn [rf]
      (let [used (volatile! #{})]
        (fn
          ([] (rf))
          ([result] (rf result))
          ([result params]
           (let [candidate (find-candidate params @used)
                 params    (assoc params :filename candidate)]
             (vswap! used conj candidate)
             (rf result params))))))))

(def ^:const ^:private
  default-partition-size 50)

(defn prepare-exports
  [exports token is-wasm]
  (letfn [(process-group [[part1 :as group]]
            ;; The browser renders a partition as a single DOM page, so it is
            ;; chunked to bound that page. A wasm export is headless, so
            ;; it does not need to be chunked, and can be rendered as a single partition.
            (if (rd/headless? {:is-wasm is-wasm :type (:type part1)})
              [(build-render group)]
              (sequence (comp (partition-all default-partition-size)
                              (map build-render))
                        group)))

          (build-render [[part1 :as part]]
            {:file-id (:file-id part1)
             :page-id (:page-id part1)
             :share-id (:share-id part1)
             :name    (:name part1)
             :token   token
             :type    (:type part1)
             :scale   (:scale part1)
             :objects (mapv part-entry->object part)})

          (part-entry->object [entry]
            {:id (:object-id entry)
             :filename (:filename entry)
             :name (:name entry)
             :suffix (:suffix entry)})]

    (let [xform (comp
                 (map #(assoc % :token token))
                 (assoc-file-name))]
      (->> (sequence xform exports)
           (d/group-by (juxt :scale :type))
           (map second)
           (into [] (mapcat process-group))))))

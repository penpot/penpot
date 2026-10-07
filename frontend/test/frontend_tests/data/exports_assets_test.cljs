;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.exports-assets-test
  "The assets export over the jobs substrate: one job per run, its
  milestones into the widget, the artifact of the completed row as the
  download, and the cancel from the widget."
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.exports.assets :as de]
   [app.main.data.jobs :as dj]
   [app.main.data.persistence :as dwp]
   [app.main.repo :as repo]
   [app.main.store :as st]
   [app.util.dom :as dom]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.events :as the]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]))

(def ^:private export {:id (uuid/next)
                       :object-id (uuid/next)
                       :type :png
                       :suffix ""
                       :scale 1})

(defn- export-with-name
  [name]
  (merge export {:name name}))

(defn- test-state
  ([] (test-state nil))
  ([export-state]
   (cond-> {:profile-id (:id export)
            :ws-conn nil}
     (some? export-state)
     (assoc :export export-state))))

(def ^:private fake-job
  "What the creation of the job answers: enough to follow it."
  {:id (uuid/next) :status "queued" :name "export-assets"})

(defn- milestone
  [stage current total counter-kind]
  {:kind :progress
   :payload {:stage stage
             :counters {counter-kind {:current current :total total}}}})

(defn- fake-watch
  "The channel of the job, as a stream of emissions the flow reads."
  [emissions]
  (apply rx/concat (map rx/of emissions)))

(defn- completed-emission
  [result]
  {:status "completed" :result result})

(t/deftest normalize-export-preserves-existing-name
  (t/is (= (export-with-name "Layer 1")
           (de/normalize-export (export-with-name "Layer 1")))))

(t/deftest normalize-export-replaces-nil-name-with-object-id
  (t/is (= (export-with-name (str (:object-id export)))
           (de/normalize-export (assoc export :name nil)))))

(t/deftest normalize-export-replaces-empty-name-with-object-id
  (t/is (= (export-with-name (str (:object-id export)))
           (de/normalize-export (assoc export :name "")))))

(t/deftest request-simple-export-runs-one-assets-job
  (t/async done
    (let [export    (export-with-name "")
          observed  (atom nil)
          downloaded (atom nil)]
      (mock/with-mocks
        {repo/cmd! (mock/stub (fn [cmd params]
                                (reset! observed {:cmd cmd :params (:params params)})
                                (rx/of fake-job)))
         dj/watch-job (mock/stub (fn [_ _]
                                   (fake-watch
                                    [(milestone :rendering 1 1 :objects)
                                     (milestone :packaging 1 1 :objects)
                                     (completed-emission {:resource-uri "https://resources/uri"
                                                          :filename "export.png"
                                                          :mtype "image/png"})])))
         dwp/force-persist-and-wait (mock/stub (fn [_] (rx/of ::force-persisted)))
         dom/trigger-download-uri (mock/stub (fn [filename mtype uri]
                                               (when filename
                                                 (reset! downloaded
                                                         {:filename filename :mtype mtype :uri uri}))))
         st/ongoing-tasks (atom #{})}
        (fn [done']
          (let [completed (fn [state]
                            (t/testing "the creation froze the normalized export"
                              (t/is (= :create-export-assets-job (:cmd @observed)))
                              (t/is (= [(export-with-name (str (:object-id export)))]
                                       (get-in @observed [:params :exports]))))

                            (t/is (= 1 (get-in state [:export :progress])))

                            (t/testing "the widget settled the completed job"
                              (t/is (false? (get-in state [:export :in-progress])))
                              (t/is (= "ended" (get-in state [:export :status])))
                              (t/is (= 1 (get-in state [:export :total]))))

                            (t/testing "the artifact of the row is the download"
                              (t/is (= {:filename "export.png"
                                        :mtype "image/png"
                                        :uri "https://resources/uri"}
                                       @downloaded))))]
            (ptk/emit! (the/prepare-store (test-state) done' completed)
                       (de/request-simple-export {:export export})
                       :the/end)))
        done))))

(t/deftest request-simple-export-reads-the-pages-of-a-frame-export
  ;; A frame names no `type`: it is the pdf of its page, and its
  ;; milestones count pages, not objects.
  (t/async done
    (let [frame        {:id (uuid/next)
                        :object-id (uuid/next)
                        :suffix ""
                        :scale 1
                        :name "Frame 1"}
          downloaded  (atom nil)]
      (mock/with-mocks
        {repo/cmd! (mock/stub (fn [_ _] (rx/of fake-job)))
         dj/watch-job (mock/stub (fn [_ _]
                                   (fake-watch
                                    [(milestone :rendering 1 2 :pages)
                                     (milestone :packaging 2 2 :pages)
                                     (completed-emission {:resource-uri "https://resources/uri"
                                                          :filename "file.pdf"
                                                          :mtype "application/pdf"})])))
         dwp/force-persist-and-wait (mock/stub (fn [_] (rx/of ::force-persisted)))
         dom/trigger-download-uri (mock/stub (fn [filename _mtype _uri]
                                               (reset! downloaded filename)))
         st/ongoing-tasks (atom #{})}
        (fn [done']
          (let [completed (fn [state]
                            (t/is (= 2 (get-in state [:export :progress])))
                            (t/is (= 2 (get-in state [:export :total])))
                            (t/is (= "file.pdf" @downloaded)))]
            (ptk/emit! (the/prepare-store (test-state) done' completed)
                       (de/request-simple-export {:export frame})
                       :the/end)))
        done))))

(t/deftest request-multiple-export-freezes-the-run-of-many
  (t/async done
    (let [items    [(assoc export :name "" :enabled true)]
          observed (atom nil)]
      (mock/with-mocks
        {repo/cmd! (mock/stub (fn [cmd params]
                                (reset! observed {:cmd cmd :params (:params params)})
                                (rx/of fake-job)))
         dj/watch-job (mock/stub (fn [_ _]
                                   (fake-watch
                                    [(milestone :rendering 1 1 :objects)
                                     (completed-emission {:resource-uri "https://resources/uri"
                                                          :filename "export.zip"
                                                          :mtype "application/zip"})])))
         dom/trigger-download-uri (mock/stub (fn [& _] nil))
         st/ongoing-tasks (atom #{})}
        (fn [done']
          (let [completed (fn [state]
                            (t/testing "the creation froze the multiple run"
                              (t/is (= true (get-in @observed [:params :force-multiple]))))
                            (t/testing "the widget followed the job"
                              (t/is (false? (get-in state [:export :in-progress])))
                              (t/is (= "ended" (get-in state [:export :status])))))]
            (ptk/emit! (the/prepare-store (test-state) done' completed)
                       (de/request-multiple-export {:exports items})
                       :the/end)))
        done))))

(t/deftest request-multiple-export-reports-the-saturation
  ;; The creation refused because the queue is full: an answer, not a
  ;; fault — the widget names it and offers the retry.
  (t/async done
    (let [items [(assoc export :name "" :enabled true)]]
      (mock/with-mocks
        {repo/cmd! (mock/stub (fn [_ _]
                                (rx/throw (ex-info "quote exceeded" {:code :max-quote-reached}))))
         st/ongoing-tasks (atom #{})}
        (fn [done']
          (let [completed (fn [state]
                            (t/testing "the widget names the exporter busy"
                              (t/is (false? (get-in state [:export :in-progress])))
                              (t/is (= :max-quote-reached (get-in state [:export :error-code])))))]
            (ptk/emit! (the/prepare-store (test-state) done' completed)
                       (de/request-multiple-export {:exports items})
                       :the/end)))
        done))))

(t/deftest cancel-export-asks-the-backend-and-settles
  (t/async done
    (let [job-id  (uuid/next)
          observed (atom nil)]
      (mock/with-mocks
        {repo/cmd! (mock/stub (fn [_ params]
                                (reset! observed params)
                                (rx/of nil)))}
        (fn [done']
          (let [completed (fn [state]
                            (t/testing "the cancel of the job the widget tracks"
                              (t/is (= {:id job-id} @observed)))
                            (t/testing "and the widget shows the cancel flying"
                              (t/is (= "cancelling" (get-in state [:export :status])))
                              (t/is (true? (get-in state [:export :in-progress])))))]
            (ptk/emit! (the/prepare-store (test-state {:job-id job-id
                                                       :in-progress true
                                                       :status "running"})
                                          done' completed)
                       (de/cancel-export)
                       :the/end)))
        done))))

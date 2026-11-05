;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.consumer-test
  "The export runner of the new tree: plan → render batch → settle,
  over a fake management http and a stub renderer. The beats paint the
  stage vocabulary in order and the settle carries the artifact; a
  failure or a cancel settles as fail-job and never leaks the beat."
  (:require
   ["node:fs/promises" :as fsp]
   ["node:os" :as os]
   ["node:path" :as path]
   ["undici" :as http]
   [app.common.schema :as sm]
   [app.common.transit :as transit]
   [app.common.uuid :as uuid]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as cstr]
   [exporter.consumer :as consumer]
   [exporter.consumer.api :as api]
   [exporter.jobs :as jobs]
   [exporter.renderer :as renderer]
   [exporter.resources :as rsc]
   [exporter.shell :as shell]))

;; ---- THE MANAGEMENT FAKE

(def ^:private calls
  "One entry per management call: [method, body]."
  (atom []))

(defn- method-of
  [uri]
  (last (cstr/split (str uri) "/")))

(defn- steps-of
  []
  (mapv first @calls))

(defn- call-of
  [method]
  (some (fn [[m body]]
          (when (= m method) body))
        @calls))

(defn- fake-fetch
  [respond]
  (reset! calls [])
  (let [original api/fetch]
    (set! api/fetch
          (fn [uri request]
            (let [method (method-of uri)
                  body   (if (instance? http/FormData (.-body request))
                           (.-body request)
                           (transit/decode-str (.-body request)))]
              (swap! calls conj [method body])
              (js/Promise.resolve #js {:status 200
                                       :text   (constantly
                                                (js/Promise.resolve
                                                 (transit/encode-str
                                                  (respond method body))))}))))
    (fn [] (set! api/fetch original))))

;; ---- THE RENDER STUB

(defn- stub-render
  "A render fn in place of the service: writes one file of `content`
  per export and calls back with it. When `captured` is an atom, it
  also keeps the last batch it received."
  ([content] (stub-render content nil))
  ([content captured]
   (fn [{:keys [exports on-object] :as task}]
     (when (some? captured)
       (reset! captured task))
     (js/Promise.
      (fn [resolve reject]
        (let [writes (mapv (fn [_]
                             (let [path (shell/tempfile (os/tmpdir) :prefix "penpot.render."
                                                        :suffix ".png")]
                               (.then (fsp/writeFile path content)
                                      (fn [_]
                                        (on-object {:path     path
                                                    :filename "rendered.png"})))))
                           exports)]
          (-> (js/Promise.all (clj->js writes))
              (.then (fn [_] (resolve nil)) reject))))))))

(defn- stub-render-fail
  []
  (fn [& _]
    (js/Promise.reject (ex-info "render boom" {}))))

;; ---- THE FIXTURE

(defn- export-params
  []
  {:exports [{:file-id   (uuid/next)
              :page-id   (uuid/next)
              :object-id (uuid/next)
              :type      :png
              :name      "test shape"
              :suffix    ""
              :scale     1}]
   :name    "the export"})

(defn- answer-with
  [sid stages]
  (fn [method body]
    (when (= method "report-job-progress")
      (vswap! stages conj (get-in body [:progress :stage])))
    (case method
      "create-job-session" {:session-id sid :session-token "session-token"}
      {:action :run})))

(t/deftest ^:async run-exports-one-shape-to-a-multipart-settle
  (let [job-id         (uuid/next)
        sid            (uuid/next)
        stages         (volatile! [])
        restore-fetch  (fake-fetch
                        (fn [method body]
                          (when (= method "report-job-progress")
                            (vswap! stages conj (get-in body [:progress :stage])))
                          (case method
                            "create-job-session" {:session-id sid :session-token "session-token"}
                            {:action :run})))
        render         (stub-render "rendered!")]
    (try
      (await (consumer/run-export render (os/tmpdir) {:job-id job-id} (export-params)))
      (t/testing "the run went by first breath, session, beats, settle"
        (t/is (= ["report-job-progress" "create-job-session"
                  "report-job-progress" "report-job-progress"
                  "complete-job"]
                 (steps-of))))
      (t/testing "the settle carried the real artifact, named and typed"
        (let [fd (call-of "complete-job")]
          (t/is (instance? http/FormData fd))
          (t/is (= (str job-id) (.get fd "job-id")))
          (t/is (= (str sid) (.get fd "session-id")))
          (t/is (= "image/png" (.-type (.get fd "content"))))))
      (t/testing "the beats painted the vocabulary in order"
        (t/is (= [:preparing :rendering :packaging] @stages)))
      (t/testing "the local registry entry is gone with the settle"
        (t/is (false? (jobs/cancelled? job-id))))
      (catch :default cause
        (t/is false (str "unexpected failure: " (ex-message cause))))
      (finally
        (restore-fetch)))))

(t/deftest ^:async run-hands-the-renderer-uuids-not-claim-strings
  (let [restore-fetch  (fake-fetch (answer-with (uuid/next) (volatile! [])))
        captured       (atom nil)
        render         (stub-render "rendered!" captured)
        job-id         (uuid/next)
        share-id       (uuid/next)]
    (try
      (await (consumer/run-export
              render (os/tmpdir)
              {:job-id job-id}
              {:exports [{:file-id   (str (uuid/next))
                          :page-id   (str (uuid/next))
                          :object-id (str (uuid/next))
                          :share-id  (str share-id)
                          :type      "png"
                          :name      "test shape"
                          :suffix    ""
                          :scale     1}]
               :name    "the export"}))
      (let [export (-> @captured :exports first)]
        (t/testing "every id arrived as a uuid object"
          (t/is (uuid? (:file-id export)))
          (t/is (uuid? (:page-id export)))
          (t/is (uuid? (:share-id export)))
          (t/is (= share-id (:share-id export)))
          (t/is (uuid? (-> export :objects first :id))))
        (t/testing "the job-id traveled with the render"
          (t/is (= job-id (:job-id export))))
        (t/testing "the task handed to the renderer satisfies the render contract"
          (t/is ((sm/validator renderer/schema:task) @captured)))
        (t/testing "the task carried both injections"
          (t/is (fn? (:on-object @captured)))
          (t/is (fn? (:check-cancelled @captured)))))
      (catch :default cause
        (t/is false (str "unexpected failure: " (ex-message cause))))
      (finally
        (restore-fetch)))))

(t/deftest ^:async run-carries-is-wasm-to-single-and-multiple
  (let [mk-params (fn [n]
                    {:exports (mapv (fn [i]
                                      {:file-id   (uuid/next)
                                       :page-id   (uuid/next)
                                       :object-id (uuid/next)
                                       :type      :png
                                       :name      (str "shape-" i)
                                       :suffix    ""
                                       :scale     1})
                                    (range n))
                     :name    "the export"
                     :is-wasm true})]
    (doseq [n [1 2]]
      (let [restore-fetch (fake-fetch (answer-with (uuid/next) (volatile! [])))
            captured      (atom nil)
            render        (stub-render "rendered!" captured)
            job-id        (uuid/next)]
        (try
          (await (consumer/run-export render (os/tmpdir) {:job-id job-id} (mk-params n)))
          (let [exports (:exports @captured)]
            (t/testing (str "every export of a batch of " n " carries :is-wasm true")
              (t/is (seq exports))
              (t/is (every? #(true? (:is-wasm %)) exports))))
          (catch :default cause
            (t/is false (str "unexpected failure: " (ex-message cause))))
          (finally
            (restore-fetch)))))))

(t/deftest ^:async run-carries-skip-children-to-multiple
  (let [mk-params (fn []
                    {:exports [{:file-id   (uuid/next)
                                :page-id   (uuid/next)
                                :object-id (uuid/next)
                                :type      :png
                                :name      "one"
                                :suffix    ""
                                :scale     1}
                               {:file-id   (uuid/next)
                                :page-id   (uuid/next)
                                :object-id (uuid/next)
                                :type      :png
                                :name      "two"
                                :suffix    ""
                                :scale     1}]
                     :name           "the export"
                     :skip-children  true})
        restore-fetch (fake-fetch (answer-with (uuid/next) (volatile! [])))
        captured      (atom nil)
        render        (stub-render "rendered!" captured)
        job-id        (uuid/next)]
    (try
      (await (consumer/run-export render (os/tmpdir) {:job-id job-id} (mk-params)))
      (let [exports (:exports @captured)]
        (t/is (seq exports))
        (t/is (= 2 (reduce + 0 (map (comp count :objects) exports))))
        (t/is (every? #(true? (:skip-children %)) exports)))
      (catch :default cause
        (t/is false (str "unexpected failure: " (ex-message cause))))
      (finally
        (restore-fetch)))))

(defn- one-shape
  [n]
  {:file-id   (uuid/next)
   :page-id   (uuid/next)
   :object-id (uuid/next)
   :type      :png
   :name      (str "shape-" n)
   :suffix    ""
   :scale     1})

(t/deftest ^:async run-leaves-no-tracked-files-behind
  ;; The artifact is tracked where make-plan creates it, so the settle
  ;; release wipes the job area on every path: without the track the
  ;; multi zip (and the frames pdf below) stays on disk.
  (let [restore-fetch (fake-fetch (answer-with (uuid/next) (volatile! [])))
        render        (stub-render "rendered!")
        check-empty   (^:async fn [label params]
                        (let [area   (path/join (os/tmpdir) (str "penpot-consumer-release-test." (uuid/next)))
                              _      (await (fsp/mkdir area #js {:recursive true}))
                              job-id (uuid/next)]
                          (try
                            (await (consumer/run-export render area {:job-id job-id} params))
                            (let [entries (await (fsp/readdir area))]
                              (t/testing (str "the job area is empty after a " label " run")
                                (t/is (empty? entries))))
                            (finally
                              (await (fsp/rm area #js {:recursive true :force true}))))))]
    (try
      (await (check-empty "single" {:exports [(one-shape "one")] :name "the export"}))
      (await (check-empty "multi" {:exports [(one-shape "one") (one-shape "two")] :name "the export"}))
      (catch :default cause
        (t/is false (str "unexpected failure: " (ex-message cause))))
      (finally
        (restore-fetch)))))

(t/deftest ^:async run-frames-leaves-no-tracked-files-behind
  (let [restore-fetch (fake-fetch (answer-with (uuid/next) (volatile! [])))
        render        (stub-render "page!")
        original-cmd  shell/run-cmd
        area          (path/join (os/tmpdir) (str "penpot-consumer-release-frames-test." (uuid/next)))
        job-id        (uuid/next)]
    (set! shell/run-cmd
          (^:async fn [_cmd & args]
            (let [dest (last (filter string? (flatten args)))]
              (await (shell/write-file dest "joined!"))
              nil)))
    (try
      (await (fsp/mkdir area #js {:recursive true}))
      (await (consumer/run-export render area {:job-id job-id}
                                  {:exports [(assoc (one-shape "page") :type :pdf)]
                                   :kind    :frames
                                   :name    "the export"}))
      (let [entries (await (fsp/readdir area))]
        (t/testing "the job area is empty after a frames run"
          (t/is (empty? entries))))
      (catch :default cause
        (t/is false (str "unexpected failure: " (ex-message cause))))
      (finally
        (restore-fetch)
        (set! shell/run-cmd original-cmd)
        (await (fsp/rm area #js {:recursive true :force true}))))))

(t/deftest ^:async run-packs-two-shapes-as-zip
  (let [restore-fetch  (fake-fetch (answer-with (uuid/next) (volatile! [])))
        render         (stub-render "rendered!")
        job-id         (uuid/next)]
    (try
      (await (consumer/run-export
              render (os/tmpdir)
              {:job-id job-id}
              {:exports [{:file-id   (uuid/next)
                          :page-id   (uuid/next)
                          :object-id (uuid/next)
                          :type      :png
                          :name      "one"
                          :suffix    ""
                          :scale     1}
                         {:file-id   (uuid/next)
                          :page-id   (uuid/next)
                          :object-id (uuid/next)
                          :type      :png
                          :name      "two"
                          :suffix    ""
                          :scale     1}]
               :name    "the export"}))
      (t/testing "the artifact is the zip of both objects"
        (let [fd (call-of "complete-job")]
          (t/is (instance? http/FormData fd))
          (t/is (= "application/zip" (.-type (.get fd "content"))))))
      (catch :default cause
        (t/is false (str "unexpected failure: " (ex-message cause))))
      (finally
        (restore-fetch)))))

(t/deftest ^:async a-failed-run-settles-fail-job-and-stops-beating
  (let [restore-fetch  (fake-fetch (answer-with (uuid/next) (volatile! [])))
        render         (stub-render-fail)
        job-id         (uuid/next)]
    (try
      (await (consumer/run-export render (os/tmpdir) {:job-id job-id} (export-params)))
      (await (js/Promise. (fn [resolve] (js/setTimeout resolve 1400))))
      (t/testing "the settle was a fail, not a complete"
        (t/is (= "fail-job" (last (steps-of)))))
      (t/testing "the failure is the shape the backend stores"
        (let [fail (call-of "fail-job")]
          (t/is (= :internal (get-in fail [:error :type])))
          (t/is (= :export-failed (get-in fail [:error :code])))
          (t/is (= "render boom" (get-in fail [:error :hint])))))
      (catch :default cause
        (t/is false (str "unexpected failure: " (ex-message cause))))
      (finally
        (restore-fetch)))))

(t/deftest ^:async a-failed-multi-run-aborts-the-zip
  ;; A render that fails after an object landed must destroy the write
  ;; stream: removing the temp path does not close the handle.
  (let [restore-fetch (fake-fetch (answer-with (uuid/next) (volatile! [])))
        original      rsc/abort-zip
        aborted       (atom 0)
        render        (fn [{:keys [on-object]}]
                        (let [path (shell/tempfile (os/tmpdir) :prefix "penpot.abort."
                                                   :suffix ".png")]
                          (-> (.then (fsp/writeFile path "rendered!")
                                     (fn [_] (on-object {:path path :filename "one.png"})))
                              (.then (fn [_] (js/Promise.reject (ex-info "render boom" {})))))))
        job-id        (uuid/next)]
    (set! rsc/abort-zip (fn [zip] (swap! aborted inc) (original zip)))
    (try
      (await (consumer/run-export render (os/tmpdir) {:job-id job-id}
                                  {:exports [(one-shape "one") (one-shape "two")]
                                   :name    "the export"}))
      (t/testing "the run settled as failed"
        (t/is (= "fail-job" (last (steps-of)))))
      (t/testing "the zip was aborted, not left open"
        (t/is (= 1 @aborted)))
      (catch :default cause
        (t/is false (str "unexpected failure: " (ex-message cause))))
      (finally
        (set! rsc/abort-zip original)
        (restore-fetch)))))

(t/deftest ^:async cancel-before-the-render-settles-cancelled
  (let [restore-fetch (fake-fetch
                       (fn [method _body]
                         (case method
                           "create-job-session" {:session-id (uuid/next)
                                                 :session-token "session-token"}
                           "report-job-progress" {:action :skip}
                           {:action :run})))
        render         (stub-render "should never render")
        job-id         (uuid/next)]
    (try
      (await (consumer/run-export render (os/tmpdir) {:job-id job-id} (export-params)))
      (t/testing "the settle of a cancelled job was a fail the backend answers skip"
        (t/is (= "fail-job" (last (steps-of))))
        (let [fail (call-of "fail-job")]
          (t/is (= :job-cancelled (get-in fail [:error :code])))))
      (catch :default cause
        (t/is false (str "unexpected failure: " (ex-message cause))))
      (finally
        (restore-fetch)))))

(t/deftest ^:async run-reports-progress-in-order
  ;; The intermediate beats are awaited, so the counters land in the
  ;; order the objects do: the widget never paints a step backwards.
  (let [progresses    (volatile! [])
        restore-fetch (fake-fetch
                       (fn [method body]
                         (when (= method "report-job-progress")
                           (vswap! progresses conj (get-in body [:progress :counters])))
                         (case method
                           "create-job-session" {:session-id   (uuid/next)
                                                 :session-token "session-token"}
                           {:action :run})))
        render        (stub-render "rendered!")
        job-id        (uuid/next)]
    (try
      (await (consumer/run-export
              render (os/tmpdir)
              {:job-id job-id}
              {:exports [(one-shape "one") (one-shape "two")]
               :name    "the export"}))
      (let [currents (into [] (comp (map :objects) (map :current) (filter some?)) @progresses)]
        (t/is (= currents (sort currents))
              "counters never step backwards"))
      (catch :default cause
        (t/is false (str "unexpected failure: " (ex-message cause))))
      (finally
        (restore-fetch)))))

(t/deftest ^:async run-joins-frame-pages-as-pdf
  ;; the frames path end to end except pdfunite itself: the page
  ;; renders, the join runs in the injected temp area, the artifact
  ;; settles as pdf. Frames travel declared (`:kind`), not sniffed:
  ;; the items are fully typed pages, as the frozen job holds them.
  (let [tmpdir         (shell/ensure-dir (path/join (os/tmpdir) "penpot-consumer-frames-test"))
        restore-fetch  (fake-fetch
                        (fn [method _body]
                          (case method
                            "create-job-session" {:session-id   (uuid/next)
                                                  :session-token "session-token"}
                            {:action :run})))
        render         (stub-render "page!")
        joined         (atom nil)
        original-cmd   shell/run-cmd
        job-id         (uuid/next)]
    (set! shell/run-cmd
          (^:async fn [cmd & args]
            (let [dest (last (filter string? (flatten args)))]
              (reset! joined [cmd dest])
              (await (shell/write-file dest "joined!"))
              nil)))
    (try
      (await (consumer/run-export render tmpdir {:job-id job-id}
                                  {:exports [{:file-id   (uuid/next)
                                              :page-id   (uuid/next)
                                              :object-id (uuid/next)
                                              :type      :pdf
                                              :name      "page"
                                              :suffix    ""
                                              :scale     1}]
                                   :kind    :frames
                                   :name    "the export"}))
      (t/testing "pdfunite joined inside the injected temp area"
        (t/is (= "pdfunite" (first @joined)))
        (t/is (cstr/starts-with? (second @joined) tmpdir)))
      (t/testing "the artifact settled as pdf"
        (let [fd (call-of "complete-job")]
          (t/is (instance? http/FormData fd))
          (t/is (= "application/pdf" (.-type (.get fd "content"))))))
      (catch :default cause
        (t/is false (str "unexpected failure: " (ex-message cause))))
      (finally
        (restore-fetch)
        (set! shell/run-cmd original-cmd)))))

;; ---- SERIALIZED BEATS

(t/deftest ^:async beats-run-one-at-a-time
  ;; The watchdog repeats the last report while a unit beat may be in
  ;; flight: serialized, the newest milestone can never be overwritten by
  ;; an older one arriving late.
  (let [chain (atom nil)
        live  (atom 0)
        peak  (atom 0)
        order (atom [])
        task  (fn [label]
                (^:async fn []
                  (swap! live inc)
                  (swap! peak max @live)
                  (swap! order conj [:start label])
                  (await (js/Promise.
                          (fn [resolve] (js/setTimeout resolve 2))))
                  (swap! order conj [:end label])
                  (swap! live dec)
                  label))]
    (await (js/Promise.all
            [(consumer/enqueue-beat chain (task :a))
             (consumer/enqueue-beat chain (task :b))
             (consumer/enqueue-beat chain (task :c))]))
    (t/testing "never two in flight"
      (t/is (= 1 @peak)))
    (t/testing "and in the order they were queued"
      (t/is (= [[:start :a] [:end :a]
                [:start :b] [:end :b]
                [:start :c] [:end :c]]
               @order)))))

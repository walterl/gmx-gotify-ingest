#!/usr/bin/env bb
(ns gmx-gotify-ingest
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [babashka.curl :as curl]
    [babashka.fs :as fs]
    [babashka.process :as proc]
    [cheshire.core :as json]))

(defn- pjoin
  [& parts]
  (str/join fs/file-separator parts))

(defn- env [s] (System/getenv s))

(defn- env!
  [s]
  (or (env s)
      (throw (ex-info (str "Environment variable not found: " s)
                      {:type ::env-var-not-found
                       :env-var s}))))

;; Gotify config
(def gotify-base (str (env! "GGI_GOTIFY_HOST") (env! "GGI_GOTIFY_PATH")))
(def gotify-app-id (env! "GGI_GOTIFY_APP_ID"))
(def gotify-app-token (env! "GGI_GOTIFY_APP_TOKEN"))

(def gotify-app-message-path (str "/application/" gotify-app-id "/message"))

;; GMX config
(def gmx-db-dir (str (fs/expand-home (env! "GGI_GMX_DB_DIR"))))
(def gmx-base-dir (str (fs/expand-home (env! "GGI_GMX_SRC_DIR"))))
(def gmx-save-dir (pjoin gmx-db-dir (or (env "GGI_GMX_OUT_DIR") "unsorted")))

(def python (pjoin gmx-base-dir "python"))
(def py-script-dir (pjoin gmx-base-dir "pygmx"))

(defn- headers
  [m]
  (merge m {"X-Gotify-Key" gotify-app-token}))

(defn- gotify-url
  [path]
  (if (str/starts-with? path gotify-base)
    path
    (str gotify-base path)))

(defn- http-get
  [path]
  (-> (curl/get (gotify-url path) {:headers (headers nil)})
      :body
      (json/parse-string true)))

(defn- http-delete
  [path]
  (curl/delete (gotify-url path) {:headers (headers nil)}))

(comment
  (http-get "/application/3/message")
  (http-get "/application/2/message")
  (http-get "/application/2/message?limit=100&since=781")
  ,)

(defn- gotify-host-pattern
  ([]
   (gotify-host-pattern (env! "GGI_GOTIFY_HOST")))
  ([srv]
   (re-pattern (str "^" (str/escape srv {\. "\\."})))))

(comment
  (gotify-host-pattern "https://gotify.wrl.co.za")
  ,)

(defn- next-page-url
  [{:keys [next]}]
  (when next
    (str/replace next (gotify-host-pattern) "")))

(defn- all-messages
  ([]
   (all-messages gotify-app-message-path))
  ([url]
   (let [{:keys [paging messages]} (http-get url)
         next-page (next-page-url paging)]
     (if next-page
       (into messages (all-messages next-page))
       (do
         (log/info (format "Received %d messages" (count messages)))
         messages)))))

(comment
  (all-messages gotify-app-message-path)
  (def nw-msgs (all-messages "/application/2/message"))
  (count nw-msgs)
  ,)

(defn- delete-msgs
  ([]
   (delete-msgs gotify-app-id))
  ([app-id]
   (http-delete (str "/application/" app-id "/message"))
   (log/info "Messages deleted.")))

(defn- write-temp-file
  [content]
  (let [content (if (str/ends-with? content "\n") content (str content \newline))
        fname (str (fs/create-temp-file {:path gmx-save-dir, :prefix "gotify_ingest", :suffix ".md"}))]
    (with-open [f (io/writer fname)]
      (.write f content))
    fname))

(defn- unique-fname
  [fname]
  (if-not (fs/exists? fname)
    fname
    (let [[path ext] (fs/split-ext fname)]
      (loop [i 1]
        (let [fname* (str path "__" i "." ext)]
          (if-not (fs/exists? fname*)
            fname*
            (recur (inc i))))))))

(defn- process
  [args]
  (-> args
      (proc/process {:out :string, :dir gmx-db-dir})
      (proc/check)))

(defn- expand-content!
  [fname]
  (log/debug "$ python expand_content.py" fname)
  (process [python (pjoin py-script-dir "expand_content.py") fname]))

(defn- fname-from-content
  [fname]
  (log/debug "$ python filename_from_content.py" fname)
  (let [expand-script (pjoin py-script-dir "filename_from_content.py")
        new-fname (-> (process [python expand-script fname])
                      :out
                      (str/trim))]
    (log/debug "→" new-fname)
    new-fname))

(defn- msg->file-content
  [{:keys [title message]}]
  (->> [(when (and title (not= "GMX" title))
          (str "# " title))
        (when message message)]
       (remove empty?)
       (str/join \newline)
       (str/trim)))

(defn- proc-msg
  [msg]
  (log/info "Processing msg:" (pr-str msg))
  (if-let [content (not-empty (msg->file-content msg))]
    (let [tmp-path (write-temp-file content)
          _expand (expand-content! tmp-path)
          new-fname (fname-from-content tmp-path)
          dest-path (unique-fname (pjoin gmx-save-dir new-fname))] ; tmp-path should be in gmx-save-dir
      (fs/move tmp-path dest-path)
      (log/info "  - New file:" dest-path)
      dest-path)
    (log/info "  - Nothing to add!")))

(defn- git-commit-files!
  [fnames msg]
  (when (seq fnames)
    (log/info "Commit files:" (pr-str fnames))
    (process (into ["git" "add"] fnames))
    (process ["git" "commit" "-m" msg])))

(defn- proc-all-msgs
  [msgs]
  (let [fnames (mapv proc-msg msgs)]
    (git-commit-files! fnames "Added by gotify ingest")
    fnames))

(comment
  (fs/cwd)
  (proc-msg {:title "Title", :message "https://ddg.co"})

  (def fpath (fs/create-temp-file {:path ".", :prefix "gotify_ingest", :suffix ".md"}))
  (str fpath)
  (with-open [f (io/writer (str fpath))]
    (.write f "test\ncontent"))
  (def f (io/file *1))
  (type f)
  (doall (map proc-msg (all-messages gotify-app-message-path)))
  (doseq [msg (all-messages gotify-app-message-path)]
    (proc-msg msg))
  ,)

(defn main
  []
  (when-not (fs/directory? gmx-save-dir)
    (fs/create-dirs gmx-save-dir))
  (when (seq (proc-all-msgs (all-messages)))
    (delete-msgs gotify-app-id)))

(when (= "run" (first *command-line-args*))
  (main))

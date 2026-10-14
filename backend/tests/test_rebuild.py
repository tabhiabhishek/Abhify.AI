"""Embedding-model changes: never mix vectors; rebuild into a new collection; switch only when valid."""
import database as db
import document_indexer as di
import knowledge
import local_file_service as lfs
import system_service as system


def _change_embed_model(monkeypatch, name):
    monkeypatch.setattr(system, "_reset_model_caches", lambda: None)
    lfs.update_settings(models={"embed": name})


def test_model_change_requires_rebuild_and_blocks_mixing(kb, monkeypatch):
    f = kb / "a.txt"; f.write_text("support vector machines maximise the margin", encoding="utf-8")
    di.index_file(f, kb)
    assert knowledge.search("margin") and not knowledge.rebuild_required()
    _change_embed_model(monkeypatch, "mxbai-embed-large")
    assert knowledge.rebuild_required()
    assert knowledge.search("margin") == []                      # no query with an incompatible model
    g = kb / "b.txt"; g.write_text("new file during mismatch", encoding="utf-8")
    assert di.index_file(g, kb)["status"] == "retry"              # not mixed into the old collection


def test_rebuild_switches_atomically_and_keeps_old(kb, monkeypatch):
    f = kb / "a.txt"; f.write_text("k-means clusters points around centroids", encoding="utf-8")
    di.index_file(f, kb)
    old_name = knowledge.active_collection()
    _change_embed_model(monkeypatch, "mxbai-embed-large")
    r = knowledge.rebuild_index()
    assert r["status"] == "done" and r["chunks"] == 1
    k = lfs.load_settings()["knowledge"]
    assert k["collection"] != old_name and k["index_embed_model"] == "mxbai-embed-large"
    assert old_name in k["previous_collections"]
    assert knowledge.client().get_collection(old_name).count() == 1       # old index preserved
    assert not knowledge.rebuild_required() and knowledge.search("centroids")
    assert db.get_document("watcher", di.path_key(f))["embed_model"] == "mxbai-embed-large"
    assert old_name in knowledge.delete_previous_collections()


def test_failed_rebuild_leaves_old_index_active(kb, monkeypatch):
    f = kb / "a.txt"; f.write_text("decision trees split on information gain", encoding="utf-8")
    di.index_file(f, kb)
    old_name = knowledge.active_collection()
    _change_embed_model(monkeypatch, "mxbai-embed-large")

    def broken(doc):
        raise FileNotFoundError("original missing")
    r = knowledge.rebuild_index(extract=broken)
    assert r["status"] == "failed"
    k = lfs.load_settings()["knowledge"]
    assert k["collection"] == old_name and k["index_embed_model"] == "nomic-embed-text"
    names = [c.name for c in knowledge.client().list_collections()]
    assert names == [old_name]                                   # half-built collection discarded


def test_legacy_collection_adopts_configured_model(kb):
    assert knowledge.index_embed_model() is None                 # M1 data had no record
    knowledge.adopt_index_model_if_unset()
    assert knowledge.index_embed_model() == "nomic-embed-text" and not knowledge.rebuild_required()


def test_uploads_are_rebuilt_too(kb, monkeypatch):
    knowledge.add_upload(b"precision recall and f1 score notes", "metrics.txt")
    _change_embed_model(monkeypatch, "mxbai-embed-large")
    assert knowledge.rebuild_index()["status"] == "done"
    assert knowledge.search("f1 score")[0].metadata["source"] == "metrics.txt"

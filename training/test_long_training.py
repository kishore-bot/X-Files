import hashlib
import json
import tempfile
import unittest
from pathlib import Path
import numpy as np
from train_long import Sampler,INDEX_DTYPE,Packed,atomic_json,local_path,ROOT
from build_edge_curriculum import deep_cases,attach_facts
from validate_dataset import validate_row


class LongTrainingTests(unittest.TestCase):
    def test_sampler_resumes_at_same_position(self):
        a=Sampler(17)
        [a.next() for _ in range(23)]
        b=Sampler(17,a.epoch,a.cursor)
        self.assertEqual([a.next() for _ in range(60)],[b.next() for _ in range(60)])

    def test_sampler_visits_all_examples_per_epoch(self):
        s=Sampler(29)
        self.assertEqual(set(s.next() for _ in range(29)),set(range(29)))

    def test_index_layout(self):
        self.assertEqual(INDEX_DTYPE.itemsize,16)

    def test_packed_masks_only_prompt(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'cache') as tmp:
            path=Path(tmp)
            self.assertTrue(path.resolve().is_relative_to((ROOT/'cache').resolve()))
            np.array([1,3,4,5,7],dtype='<i4').tofile(path/'tokens.bin')
            np.array([(0,5,3)],dtype=INDEX_DTYPE).tofile(path/'index.bin')
            (path/'manifest.json').write_text('{"targets":1,"tokens":5}')
            dataset=Packed(path)
            batch,count=dataset.batch(0,device='cpu')
            self.assertEqual(count,2)
            self.assertEqual(batch['labels'].tolist(),[[-100,-100,-100,5,7]])
            # Close Windows memory maps before TemporaryDirectory cleanup.
            dataset.tokens._mmap.close();dataset.index._mmap.close()

    def test_path_escape_rejected(self):
        with self.assertRaises(ValueError):local_path('../outside-training')

    def test_deep_cases_validate(self):
        for split in ['train','validation','test']:
            rows=list(deep_cases(split,2))
            self.assertGreater(len(rows),100)
            for row in rows:validate_row(row)

    def test_ambiguous_status_prompt_repaired(self):
        caps=json.loads((ROOT/'data/app_capabilities.json').read_text())
        from build_hard_dataset import build
        rows=build('hard_train',1)
        row=next(r for r in rows if r['family']=='contrast_status_chooser_opened')
        fixed=attach_facts(row,caps)
        self.assertEqual(fixed['messages'][2]['content'],'Open sharing for the selected file.')

    def test_manifest_files_and_tool_coverage(self):
        root=ROOT/'data/edge_v5'
        manifest=json.loads((root/'manifest.json').read_text())
        from catalog import TOOLS
        for split,info in manifest['splits'].items():
            self.assertEqual(set(info['tools']),set(TOOLS))
            with (root/f'{split}.jsonl').open('rb') as f:
                self.assertEqual(hashlib.file_digest(f,'sha256').hexdigest(),info['sha256'])
            self.assertGreaterEqual(len(info['families']),200)

    def test_job_lock_blocks_duplicate_worker(self):
        from run_five_hour import job_lock
        with tempfile.TemporaryDirectory(dir=ROOT/'cache') as tmp:
            path=Path(tmp).resolve()
            self.assertTrue(path.is_relative_to((ROOT/'cache').resolve()))
            with job_lock(path/'worker.lock'):
                with self.assertRaises(OSError):
                    with job_lock(path/'worker.lock'):pass


if __name__=='__main__':unittest.main()

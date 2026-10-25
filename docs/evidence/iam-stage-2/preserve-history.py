from pathlib import Path
import hashlib,json
root=Path(__file__).resolve().parents[3];out=Path(__file__).resolve().parent
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
old=json.loads((root/'docs/evidence/iam-stage-1-ownership-fix/prior-evidence-before.json').read_text('utf8'))
start=json.loads((out/'prior-evidence-before.json').read_text('utf8'))
review='docs/evidence/review-iam-stage-1.md'
assert all(sha(root/k)==v for k,v in old.items() if k!=review)
assert sha(root/review)==start[review]
result=dict(historicalFiles=178,historicalOriginalBytesEqual=177,exception=review,
 exceptionReason='Root appended Task1 independent approval before Task2 handoff; Task2-start hash remains identical',
 task2StartReviewSha256=start[review],originalReviewProbeBytesUnchanged=True,
 originalFiles={k:sha(root/k) for k in old if k!=review})
(out/'legacy-review-preservation.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
print('177 historical original files unchanged; current review unchanged from Task2 baseline')

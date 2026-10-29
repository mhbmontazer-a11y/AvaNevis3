"""Minimal online Persian speech transcription gateway.
Deploy behind HTTPS. Never embed OPENAI_API_KEY in the Android APK.
"""
import os
import hmac
from fastapi import FastAPI, Header, HTTPException, Request
import httpx

app = FastAPI(title="AvaNevis Online ASR Gateway")
MAX_AUDIO_BYTES = 1_000_000

@app.get("/health")
async def health():
    return {"status": "ok"}

@app.post("/transcribe")
async def transcribe(request: Request, x_avanevis_token: str = Header(default="")):
    secret = os.environ.get("AVANEVIS_ACCESS_TOKEN", "")
    provider_key = os.environ.get("OPENAI_API_KEY", "")
    if not secret or not provider_key:
        raise HTTPException(503, "Server credentials are not configured")
    if not hmac.compare_digest(x_avanevis_token, secret):
        raise HTTPException(401, "Unauthorized")
    if request.headers.get("content-type", "").split(";")[0] != "audio/wav":
        raise HTTPException(415, "Expected audio/wav")
    body = await request.body()
    if len(body) < 44 or len(body) > MAX_AUDIO_BYTES or not body.startswith(b"RIFF"):
        raise HTTPException(413, "Invalid audio payload")
    async with httpx.AsyncClient(timeout=90.0) as client:
        try:
            response = await client.post(
                "https://api.openai.com/v1/audio/transcriptions",
                headers={"Authorization": f"Bearer {provider_key}"},
                files={"file": ("speech.wav", body, "audio/wav")},
                data={"model": os.getenv("TRANSCRIPTION_MODEL", "whisper-1"), "language": "fa"},
            )
            response.raise_for_status()
            text = response.json().get("text", "")
            return {"text": text.strip()}
        except (httpx.HTTPError, ValueError) as error:
            # Do not expose provider tokens or raw response bodies to clients.
            raise HTTPException(502, "Transcription provider temporarily unavailable") from error

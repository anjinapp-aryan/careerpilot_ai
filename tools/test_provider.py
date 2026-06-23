#!/usr/bin/env python3
"""
Configurable AI Provider Test Utility

Tests API connectivity and basic functionality for any LLM provider.
Supports: Gemini, GLM, Groq, DeepSeek, Qwen, and custom OpenAI-compatible endpoints.

Usage:
  # Install dependencies first
  pip install python-dotenv openai google-generativeai

  # Test with env vars (reads from .env automatically via python-dotenv)
  python test_provider.py --provider glm
  python test_provider.py --provider groq
  python test_provider.py --provider gemini
  python test_provider.py --provider deepseek

  # Override env vars via CLI
  python test_provider.py --provider custom --api-key YOUR_KEY --base-url https://... --model model-name

  # Verbose output
  python test_provider.py --provider glm --verbose
"""

import argparse
import os
import sys
from typing import Optional
from dataclasses import dataclass

try:
    from dotenv import load_dotenv
except ImportError:
    print("[WARN] python-dotenv not found. Install with: pip install python-dotenv")
    load_dotenv = lambda: None

# Load .env if available
load_dotenv()

# Encoding fix for Windows emoji support
import io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')


@dataclass
class ProviderConfig:
    """Provider configuration."""
    name: str
    api_key: str
    model: str
    base_url: str
    provider_type: str  # 'google' for Gemini, 'openai' for others


PROVIDER_DEFAULTS = {
    "gemini": {
        "model": os.getenv("GEMINI_MODEL", "gemini-2.5-flash"),
        "api_key_env": "GEMINI_API_KEY",
        "base_url": "https://generativelanguage.googleapis.com/v1beta",
        "provider_type": "google",
    },
    "groq": {
        "model": os.getenv("GROQ_MODEL", "llama-3.3-70b-versatile"),
        "api_key_env": "GROQ_API_KEY",
        "base_url": os.getenv("GROQ_BASE_URL", "https://api.groq.com/openai/v1"),
        "provider_type": "openai",
    },
    "deepseek": {
        "model": os.getenv("NVIDIA_DEEPSEEK_MODEL", "deepseek-ai/deepseek-v4-flash"),
        "api_key_env": "DEEP_SHEEK_NVIDIA_API_KEY",
        "base_url": os.getenv("NVIDIA_BASE_URL", "https://integrate.api.nvidia.com/v1"),
        "provider_type": "openai",
    },
    "qwen": {
        "model": os.getenv("NVIDIA_QWEN_MODEL", "qwen/qwen3-next-80b-a3b-instruct"),
        "api_key_env": "QWEN3_NVIDIA_API_KEY",
        "base_url": os.getenv("NVIDIA_BASE_URL", "https://integrate.api.nvidia.com/v1"),
        "provider_type": "openai",
    },
    "openrouter": {
        "model": os.getenv("OPENROUTER_MODEL", "qwen/qwen3-next-80b-a3b-instruct:free"),
        "api_key_env": "OPENROUTER_API_KEY",
        "base_url": os.getenv("OPENROUTER_BASE_URL", "https://openrouter.ai/api/v1"),
        "provider_type": "openai",
    },
}


def load_provider_config(
    provider: str,
    api_key: Optional[str] = None,
    model: Optional[str] = None,
    base_url: Optional[str] = None,
) -> ProviderConfig:
    """Load provider configuration from defaults or CLI overrides."""
    if provider not in PROVIDER_DEFAULTS and provider != "custom":
        available = ", ".join(PROVIDER_DEFAULTS.keys())
        raise ValueError(
            f"Unknown provider: {provider}. Available: {available}, or use 'custom' with --api-key, --model, --base-url"
        )

    if provider == "custom":
        if not api_key or not model or not base_url:
            raise ValueError(
                "Custom provider requires --api-key, --model, and --base-url"
            )
        return ProviderConfig(
            name="custom",
            api_key=api_key,
            model=model,
            base_url=base_url,
            provider_type="openai",  # Default to OpenAI-compatible
        )

    defaults = PROVIDER_DEFAULTS[provider]
    api_key_env = defaults["api_key_env"]
    env_api_key = os.getenv(api_key_env)

    final_api_key = api_key or env_api_key
    final_model = model or defaults["model"]
    final_base_url = base_url or defaults["base_url"]

    if not final_api_key:
        raise ValueError(
            f"No API key found. Set {api_key_env} in .env or pass --api-key"
        )

    return ProviderConfig(
        name=provider,
        api_key=final_api_key,
        model=final_model,
        base_url=final_base_url,
        provider_type=defaults["provider_type"],
    )


def test_google_provider(config: ProviderConfig, verbose: bool = False) -> bool:
    """Test Google Generative AI (Gemini) provider."""
    try:
        import google.generativeai as genai
    except ImportError:
        print("❌ google-generativeai not installed. Install with: pip install google-generativeai")
        return False

    try:
        genai.configure(api_key=config.api_key)
        model = genai.GenerativeModel(config.model)

        if verbose:
            print(f"📝 Testing {config.name.upper()} ({config.model})")
            print(f"   API Key: {config.api_key[:10]}...{config.api_key[-10:]}")

        response = model.generate_content("Say 'Hello, I work!' and nothing else.")
        text = response.text.strip() if response.text else ""

        if verbose:
            print(f"   Response: {text}")

        if "Hello" in text or "work" in text:
            print(f"✅ {config.name.upper()} provider is working!")
            return True
        else:
            print(f"⚠️  {config.name.upper()} returned unexpected response: {text}")
            return True  # API call succeeded, just unexpected content

    except Exception as e:
        print(f"❌ {config.name.upper()} provider failed: {e}")
        if verbose:
            import traceback
            traceback.print_exc()
        return False


def test_openai_compatible_provider(config: ProviderConfig, verbose: bool = False) -> bool:
    """Test OpenAI-compatible providers (GLM, Groq, DeepSeek, Qwen)."""
    try:
        from openai import OpenAI
    except ImportError:
        print("❌ openai package not installed. Install with: pip install openai")
        return False

    try:
        client = OpenAI(api_key=config.api_key, base_url=config.base_url)

        if verbose:
            print(f"📝 Testing {config.name.upper()} ({config.model})")
            print(f"   Base URL: {config.base_url}")
            print(f"   API Key: {config.api_key[:10]}...{config.api_key[-10:]}")

        response = client.chat.completions.create(
            model=config.model,
            messages=[
                {
                    "role": "user",
                    "content": "Say 'Hello, I work!' and nothing else.",
                }
            ],
            temperature=0.7,
            max_tokens=100,
        )

        text = response.choices[0].message.content.strip() if response.choices else ""

        if verbose:
            print(f"   Response: {text}")
            print(f"   Usage: {response.usage.total_tokens} tokens")

        if "Hello" in text or "work" in text:
            print(f"✅ {config.name.upper()} provider is working!")
            return True
        else:
            print(f"⚠️  {config.name.upper()} returned unexpected response: {text}")
            return True  # API call succeeded

    except Exception as e:
        print(f"❌ {config.name.upper()} provider failed: {e}")
        if verbose:
            import traceback
            traceback.print_exc()
        return False


def main():
    parser = argparse.ArgumentParser(
        description="Test AI provider API connectivity and functionality.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument(
        "--provider",
        type=str,
        default="glm",
        help="Provider to test: gemini, glm, groq, deepseek, qwen, or custom",
    )
    parser.add_argument(
        "--api-key",
        type=str,
        help="Override API key (CLI takes precedence over .env)",
    )
    parser.add_argument(
        "--model",
        type=str,
        help="Override model name",
    )
    parser.add_argument(
        "--base-url",
        type=str,
        help="Override base URL (for OpenAI-compatible providers)",
    )
    parser.add_argument(
        "--verbose",
        "-v",
        action="store_true",
        help="Verbose output with request/response details",
    )

    args = parser.parse_args()

    try:
        config = load_provider_config(
            provider=args.provider,
            api_key=args.api_key,
            model=args.model,
            base_url=args.base_url,
        )

        if config.provider_type == "google":
            success = test_google_provider(config, verbose=args.verbose)
        else:  # openai-compatible
            success = test_openai_compatible_provider(config, verbose=args.verbose)

        sys.exit(0 if success else 1)

    except ValueError as e:
        print(f"❌ Configuration error: {e}")
        sys.exit(1)
    except Exception as e:
        print(f"❌ Unexpected error: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)


if __name__ == "__main__":
    main()

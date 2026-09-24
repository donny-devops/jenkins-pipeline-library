import pytest
from app import process_payment

def test_valid_payment():
    res = process_payment(100.0, "USD")
    assert res["status"] == "approved"
    assert res["amount"] == 100.0

def test_invalid_payment():
    with pytest.raises(ValueError):
        process_payment(-5.0)

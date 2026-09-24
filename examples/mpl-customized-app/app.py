# Minimal payment service app
def process_payment(amount: float, currency: str = "USD") -> dict:
    if amount <= 0:
        raise ValueError("Amount must be greater than zero")
    return {"status": "approved", "amount": amount, "currency": currency}

if __name__ == "__main__":
    print("Payment service running.")
